package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.AppointmentRevenueRowDTO;
import com.clinic.healinghouse.dto.ComboDiscountSummaryDTO;
import com.clinic.healinghouse.dto.ProductRevenueSummaryDTO;
import com.clinic.healinghouse.dto.RevenueByCatalogItemDTO;
import com.clinic.healinghouse.dto.RevenueByPaymentMethodDTO;
import com.clinic.healinghouse.dto.RevenueByTherapistDTO;
import com.clinic.healinghouse.dto.RevenueReportDTO;
import com.clinic.healinghouse.dto.RevenueReportFilter;
import com.clinic.healinghouse.dto.RevenueSummaryDTO;
import com.clinic.healinghouse.dto.RevenueTrendDTO;
import com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO;
import com.clinic.healinghouse.dto.TherapistRevenueDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.AppointmentComboRepository;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the Actual Revenue report (/reports/revenue): real, post-discount billed/collected figures,
 * as opposed to the pre-discount commission-base figures the other five reports show.
 * See requirements/Actual_Revenue_Reporting_Requirements_v1.md.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RevenueReportAggregator {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentComboRepository appointmentComboRepository;
    private final AppointmentServiceLineRepository serviceLineRepository;
    private final AppointmentProductLineRepository productLineRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final HealingHouseProperties properties;

    public RevenueReportDTO getRevenueReport(RevenueReportFilter filter, Pageable pageable) {
        LocalDate dateFrom = filter.dateFrom();
        LocalDate dateTo = filter.dateTo();

        Specification<Appointment> baseSpec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.betweenDates(dateFrom.atStartOfDay(), dateTo.atTime(LocalTime.MAX)))
                .and(AppointmentSpec.hasTherapistId(filter.therapistId()))
                .and(AppointmentSpec.patientNameOrPhoneContains(filter.patientName()))
                .and(AppointmentSpec.hasServiceId(filter.serviceId()))
                .and(AppointmentSpec.hasProductId(filter.productId()))
                .and(AppointmentSpec.hasTagName(filter.tagName()))
                .and(AppointmentSpec.hasPaymentMethod(filter.paymentMethod()))
                .and(AppointmentSpec.isDiscountedOnly(filter.discountedOnly()));

        // Aggregation basis: always COMPLETED regardless of the status filter, per the Decided rule
        // that summary cards/breakdowns/trend can never be inflated by non-revenue statuses.
        Specification<Appointment> completedSpec = baseSpec.and(AppointmentSpec.hasStatus(AppointmentStatus.COMPLETED));
        List<Appointment> completed = appointmentRepository.findAll(completedSpec);
        List<Long> completedIds = completed.stream().map(Appointment::getId).toList();

        // Advance received on appointments not (yet) completed: money the clinic already has in hand from
        // a patient's advance payment — either a Scheduled appointment still awaiting its outcome, or a
        // Cancelled/No-Show one where the patient forfeited it (wallet-sourced amounts are already reversed
        // by AppointmentService for the latter two, so amountPaid here is real cash/UPI/card money kept).
        // Shown only as its own standalone "Advance Payments" figure — never folded into Net Revenue,
        // Collected, or any other headline/breakdown figure, all of which stay strictly COMPLETED-only
        // (revenue is recognized at completion, matching the wallet feature's own principle).
        Specification<Appointment> advanceSpec = baseSpec.and(AppointmentSpec.hasStatusIn(
                List.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)));
        List<Appointment> withAdvance = appointmentRepository.findAll(advanceSpec);

        Map<Long, BigDecimal> comboDiscountByAppointment = comboDiscountMap(completedIds);

        RevenueSummaryDTO summary = buildSummary(dateFrom, dateTo, completed, withAdvance, comboDiscountByAppointment);
        List<RevenueByPaymentMethodDTO> byPaymentMethod = buildByPaymentMethod(completed, summary.walletFunded());
        List<RevenueByTherapistDTO> byTherapist = buildByTherapist(completedIds);
        List<RevenueByCatalogItemDTO> servicesNetRevenue = buildServicesBreakdown(completedIds);
        List<RevenueByCatalogItemDTO> productsNetRevenue = buildProductsBreakdown(completedIds);
        RevenueTrendDTO trend = buildTrend(dateFrom, dateTo, completed);

        // Drill-down table: respects the caller's status choice (null = every status), visibility only —
        // never feeds the aggregation above.
        Specification<Appointment> drilldownSpec = baseSpec.and(AppointmentSpec.hasStatus(filter.status()));
        Page<Appointment> drilldownPage = appointmentRepository.findAll(drilldownSpec, pageable);
        Page<AppointmentRevenueRowDTO> appointmentsPage = drilldownPage.map(this::toRow);

        return new RevenueReportDTO(dateFrom, dateTo, summary, byPaymentMethod, byTherapist,
                servicesNetRevenue, productsNetRevenue, trend, appointmentsPage);
    }

    private Map<Long, BigDecimal> comboDiscountMap(List<Long> appointmentIds) {
        if (appointmentIds.isEmpty()) return Map.of();
        Map<Long, BigDecimal> map = new HashMap<>();
        for (ComboDiscountSummaryDTO d : appointmentComboRepository.sumDiscountByAppointmentIds(appointmentIds)) {
            map.put(d.appointmentId(), d.discountAmount());
        }
        return map;
    }

    private RevenueSummaryDTO buildSummary(LocalDate dateFrom, LocalDate dateTo, List<Appointment> completed,
                                            List<Appointment> withAdvance,
                                            Map<Long, BigDecimal> comboDiscountByAppointment) {
        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal manualDiscount = BigDecimal.ZERO;
        BigDecimal comboDiscount = BigDecimal.ZERO;
        BigDecimal netRevenue = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal walletFunded = BigDecimal.ZERO;

        for (Appointment a : completed) {
            grossRevenue = grossRevenue.add(nz(a.getTotalServiceAmount())).add(nz(a.getTotalProductAmount()));
            manualDiscount = manualDiscount.add(nz(a.getDiscountAmount()));
            comboDiscount = comboDiscount.add(comboDiscountByAppointment.getOrDefault(a.getId(), BigDecimal.ZERO));
            netRevenue = netRevenue.add(nz(a.getGrandTotal()));
            collected = collected.add(nz(a.getAmountPaid()));
            outstanding = outstanding.add(a.getBalanceDue());
            walletFunded = walletFunded.add(nz(a.getWalletAmountApplied()));
        }

        // Advance received on non-COMPLETED appointments is shown as its own informational figure (the
        // "Advance Payments" card) — never folded into netRevenue/collected/walletFunded, which stay
        // strictly COMPLETED-only per the "revenue recognized at completion" rule (CLAUDE.md): summary
        // cards/totals only ever count COMPLETED appointments, regardless of the status filter chosen.
        BigDecimal advanceReceived = BigDecimal.ZERO;
        for (Appointment a : withAdvance) {
            advanceReceived = advanceReceived.add(nz(a.getAmountPaid()));
        }

        return new RevenueSummaryDTO(dateFrom, dateTo, completed.size(), grossRevenue, comboDiscount,
                manualDiscount, netRevenue, collected, outstanding, walletFunded, advanceReceived);
    }

    /** Cash-portion collected grouped by payment method, COMPLETED appointments only (matching the
     * summary cards this breakdown sits next to), plus a synthetic "Wallet" row for the wallet-funded
     * portion — avoids double-counting wallet draws as both "collected via method X" and "wallet". */
    private List<RevenueByPaymentMethodDTO> buildByPaymentMethod(List<Appointment> completed,
                                                                  BigDecimal walletFunded) {
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (Appointment a : completed) {
            BigDecimal cashPortion = nz(a.getAmountPaid()).subtract(nz(a.getWalletAmountApplied()));
            if (cashPortion.signum() == 0) continue;
            String label = a.getPaymentMethod() != null ? a.getPaymentMethod().name() : "Unspecified";
            byMethod.merge(label, cashPortion, BigDecimal::add);
        }

        List<RevenueByPaymentMethodDTO> result = new ArrayList<>();
        byMethod.forEach((label, amount) -> result.add(new RevenueByPaymentMethodDTO(label, amount)));
        if (walletFunded.signum() > 0) {
            result.add(new RevenueByPaymentMethodDTO("Wallet", walletFunded));
        }
        result.sort(Comparator.comparing(RevenueByPaymentMethodDTO::amount).reversed());
        return result;
    }

    private List<RevenueByTherapistDTO> buildByTherapist(List<Long> appointmentIds) {
        if (appointmentIds.isEmpty()) return List.of();

        Map<Long, BigDecimal> gross = new LinkedHashMap<>();
        Map<Long, BigDecimal> net = new LinkedHashMap<>();
        Map<Long, String> namesById = new LinkedHashMap<>();
        mergeRevenue(gross, namesById, serviceLineRepository.sumRawServiceRevenueByTherapistInAppointmentIds(appointmentIds));
        mergeRevenue(gross, namesById, productLineRepository.sumRawProductRevenueByTherapistInAppointmentIds(appointmentIds));
        mergeRevenue(net, namesById, serviceLineRepository.sumEffectiveServiceRevenueByTherapistInAppointmentIds(appointmentIds));
        mergeRevenue(net, namesById, productLineRepository.sumEffectiveProductRevenueByTherapistInAppointmentIds(appointmentIds));

        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(gross.keySet());
        ids.addAll(net.keySet());

        return ids.stream()
                .map(therapistId -> {
                    BigDecimal g = gross.getOrDefault(therapistId, BigDecimal.ZERO);
                    BigDecimal n = net.getOrDefault(therapistId, BigDecimal.ZERO);
                    return new RevenueByTherapistDTO(therapistId, namesById.get(therapistId), g, g.subtract(n), n);
                })
                .sorted(Comparator.comparing(RevenueByTherapistDTO::netRevenue).reversed())
                .toList();
    }

    private static void mergeRevenue(Map<Long, BigDecimal> target, Map<Long, String> namesById,
                                      List<TherapistRevenueDTO> source) {
        for (TherapistRevenueDTO r : source) {
            target.merge(r.therapistId(), r.revenue(), BigDecimal::add);
            namesById.putIfAbsent(r.therapistId(), r.therapistName());
        }
    }

    private List<RevenueByCatalogItemDTO> buildServicesBreakdown(List<Long> appointmentIds) {
        if (appointmentIds.isEmpty()) return List.of();

        List<ServiceRevenueSummaryDTO> summaries =
                serviceLineRepository.sumEffectiveServiceRevenueByServiceInAppointmentIds(appointmentIds);
        if (summaries.isEmpty()) return List.of();

        List<Long> serviceIds = summaries.stream().map(ServiceRevenueSummaryDTO::serviceId).toList();
        Map<Long, ClinicService> servicesById = clinicServiceRepository.findAllById(serviceIds).stream()
                .collect(Collectors.toMap(ClinicService::getId, s -> s));

        return summaries.stream()
                .map(s -> {
                    ClinicService service = servicesById.get(s.serviceId());
                    List<String> tags = service != null
                            ? service.getSortedTags().stream().map(Tag::getName).toList()
                            : List.of();
                    return new RevenueByCatalogItemDTO(s.serviceName(), tags, s.bookingsCount(), s.revenue());
                })
                .sorted(Comparator.comparing(RevenueByCatalogItemDTO::netRevenue).reversed())
                .toList();
    }

    private List<RevenueByCatalogItemDTO> buildProductsBreakdown(List<Long> appointmentIds) {
        if (appointmentIds.isEmpty()) return List.of();

        List<ProductRevenueSummaryDTO> summaries =
                productLineRepository.sumEffectiveProductRevenueByProductInAppointmentIds(appointmentIds);
        if (summaries.isEmpty()) return List.of();

        List<Long> productIds = summaries.stream().map(ProductRevenueSummaryDTO::productId).toList();
        Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        return summaries.stream()
                .map(s -> {
                    Product product = productsById.get(s.productId());
                    List<String> tags = product != null
                            ? product.getSortedTags().stream().map(Tag::getName).toList()
                            : List.of();
                    return new RevenueByCatalogItemDTO(s.productName(), tags, s.unitsSold(), s.revenue());
                })
                .sorted(Comparator.comparing(RevenueByCatalogItemDTO::netRevenue).reversed())
                .toList();
    }

    private RevenueTrendDTO buildTrend(LocalDate dateFrom, LocalDate dateTo, List<Appointment> completed) {
        Map<LocalDate, BigDecimal> byDay = new HashMap<>();
        for (Appointment a : completed) {
            LocalDate day = a.getAppointmentDateTime().toLocalDate();
            byDay.merge(day, nz(a.getGrandTotal()), BigDecimal::add);
        }

        List<String> labels = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        DateTimeFormatter trendLabelFormat = DateTimeFormatter.ofPattern(properties.getReports().getTrendLabelFormat());
        for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
            labels.add(day.format(trendLabelFormat));
            values.add(byDay.getOrDefault(day, BigDecimal.ZERO));
        }
        return new RevenueTrendDTO(labels, values);
    }

    private AppointmentRevenueRowDTO toRow(Appointment a) {
        BigDecimal gross = nz(a.getTotalServiceAmount()).add(nz(a.getTotalProductAmount()));
        BigDecimal discount = nz(a.getDiscountAmount()).add(a.getTotalComboDiscount());
        return new AppointmentRevenueRowDTO(a.getId(), a.getAppointmentDateTime(), a.getPatient().getFullName(),
                a.getTherapist().getFullName(), a.getStatus(), gross, discount, nz(a.getGrandTotal()),
                nz(a.getAmountPaid()), a.getBalanceDue(), a.getPaymentMethod());
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
