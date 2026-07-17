package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ComparisonReportDTO;
import com.clinic.healinghouse.dto.DailyReportDTO;
import com.clinic.healinghouse.dto.PatientFirstVisitDTO;
import com.clinic.healinghouse.dto.PatientReportDTO;
import com.clinic.healinghouse.dto.PatientTrendDTO;
import com.clinic.healinghouse.dto.PeriodReportDTO;
import com.clinic.healinghouse.dto.PeriodSummaryDTO;
import com.clinic.healinghouse.dto.PerformanceReportDTO;
import com.clinic.healinghouse.dto.ProductPerformanceDTO;
import com.clinic.healinghouse.dto.ProductRevenueSummaryDTO;
import com.clinic.healinghouse.dto.RevenueReportDTO;
import com.clinic.healinghouse.dto.RevenueReportFilter;
import com.clinic.healinghouse.dto.ServicePerformanceDTO;
import com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO;
import com.clinic.healinghouse.dto.ServiceTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.dto.TagTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TherapistEarningsDTO;
import com.clinic.healinghouse.dto.TherapistPatientMetricsDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.repository.TherapistRepository;
import com.clinic.healinghouse.security.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the five Phase 3 report views on top of {@link ReportAggregator}, {@link CommissionCalculator},
 * and {@link DashboardService} — the per-line commission/revenue engine is reused rather than duplicated.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportAggregator reportAggregator;
    private final RevenueReportAggregator revenueReportAggregator;
    private final DashboardService dashboardService;
    private final TherapistRepository therapistRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentServiceLineRepository serviceLineRepository;
    private final AppointmentProductLineRepository productLineRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final HealingHouseProperties properties;
    private final PermissionService permissionService;

    public DailyReportDTO getDailyReport(LocalDate date) {
        PeriodSummaryDTO summary = reportAggregator.getPeriodSummary(date, date);
        List<TherapistEarningsDTO> therapistEarnings = scopeToOwnTherapist(reportAggregator.getTherapistEarnings(date, date));
        return new DailyReportDTO(date, summary, therapistEarnings);
    }

    public PeriodReportDTO getPeriodReport(LocalDate dateFrom, LocalDate dateTo) {
        PeriodSummaryDTO summary = reportAggregator.getPeriodSummary(dateFrom, dateTo);
        List<TherapistEarningsDTO> therapistEarnings = scopeToOwnTherapist(reportAggregator.getTherapistEarnings(dateFrom, dateTo));
        List<TagRevenueDTO> tagRevenue = dashboardService.getTagRevenueBreakdown(dateFrom, dateTo);
        List<ProductPerformanceDTO> productPerformance = buildProductPerformance(dateFrom, dateTo);
        return new PeriodReportDTO(dateFrom, dateTo, summary, therapistEarnings, tagRevenue, productPerformance);
    }

    public ComparisonReportDTO getTherapistComparison(List<Long> therapistIds, LocalDate dateFrom, LocalDate dateTo) {
        // Defense in depth: a THERAPIST can never compare against another therapist's row, even if
        // the controller's own size>=2 pre-check were bypassed (requirements/Security_RBAC_Requirements_v1.md §7).
        Long ownTherapistId = permissionService.currentTherapistId();
        List<Long> effectiveIds = ownTherapistId != null ? List.of(ownTherapistId) : therapistIds;
        List<Therapist> therapists = therapistRepository.findAllById(effectiveIds);
        List<TherapistEarningsDTO> earnings = reportAggregator.getTherapistEarnings(therapists, dateFrom, dateTo);
        return new ComparisonReportDTO(dateFrom, dateTo, earnings);
    }

    /** THERAPIST role: reports never show another therapist's earnings row, even under a tampered
     *  client request (requirements/Security_RBAC_Requirements_v1.md §7). */
    private List<TherapistEarningsDTO> scopeToOwnTherapist(List<TherapistEarningsDTO> earnings) {
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId == null) return earnings;
        return earnings.stream()
                .filter(e -> ownTherapistId.equals(e.therapist().getId()))
                .toList();
    }

    public RevenueReportDTO getRevenueReport(RevenueReportFilter filter, Pageable pageable) {
        return revenueReportAggregator.getRevenueReport(filter, pageable);
    }

    public PerformanceReportDTO getProductPerformanceReport(LocalDate dateFrom, LocalDate dateTo) {
        Long ownTherapistId = permissionService.currentTherapistId();
        List<ServicePerformanceDTO> services = buildServicePerformance(dateFrom, dateTo, ownTherapistId);
        List<ProductPerformanceDTO> products = buildProductPerformance(dateFrom, dateTo);
        List<TagRevenueDTO> tagRevenue = dashboardService.getTagRevenueBreakdown(dateFrom, dateTo);
        List<TagTherapistRevenueDTO> tagTherapistRevenue = buildTagTherapistRevenue(dateFrom, dateTo).stream()
                .filter(r -> ownTherapistId == null || ownTherapistId.equals(r.therapistId()))
                .toList();
        return new PerformanceReportDTO(dateFrom, dateTo, services, products, tagRevenue, tagTherapistRevenue);
    }

    /**
     * A patient is "new" if their earliest-ever appointment (any status) falls within
     * [dateFrom, dateTo]; otherwise they are "repeat". Per-therapist metrics use the
     * appointment's main therapist, since acquisition/retention is a visit-level concept.
     */
    public PatientReportDTO getPatientAcquisitionReport(LocalDate dateFrom, LocalDate dateTo) {
        LocalDateTime start = dateFrom.atStartOfDay();
        LocalDateTime end = dateTo.atTime(LocalTime.MAX);

        List<Appointment> appointmentsInRange = appointmentRepository.findByDateRange(start, end);
        Map<Long, LocalDateTime> firstVisitByPatient = appointmentRepository
                .findFirstVisitDatesForPatientsActiveInRange(start, end).stream()
                .collect(Collectors.toMap(PatientFirstVisitDTO::patientId, PatientFirstVisitDTO::firstVisitDateTime));

        Set<Long> newPatientIds = new HashSet<>();
        Set<Long> repeatPatientIds = new HashSet<>();
        Map<Long, Set<Long>> newPatientIdsByTherapistId = new LinkedHashMap<>();
        Map<Long, Set<Long>> repeatPatientIdsByTherapistId = new LinkedHashMap<>();

        for (Appointment a : appointmentsInRange) {
            Long patientId = a.getPatient().getId();
            Long therapistId = a.getTherapist().getId();
            boolean isNew = isNewPatient(firstVisitByPatient.get(patientId), start);

            (isNew ? newPatientIds : repeatPatientIds).add(patientId);
            (isNew ? newPatientIdsByTherapistId : repeatPatientIdsByTherapistId)
                    .computeIfAbsent(therapistId, k -> new HashSet<>()).add(patientId);
        }

        List<Therapist> therapists = therapistRepository.findByActiveTrueOrderByFullNameAsc();
        Long ownTherapistId = permissionService.currentTherapistId();
        List<TherapistPatientMetricsDTO> therapistMetrics = therapists.stream()
                .filter(t -> ownTherapistId == null || ownTherapistId.equals(t.getId()))
                .map(t -> {
                    long newCount = newPatientIdsByTherapistId.getOrDefault(t.getId(), Set.of()).size();
                    long repeatCount = repeatPatientIdsByTherapistId.getOrDefault(t.getId(), Set.of()).size();
                    return new TherapistPatientMetricsDTO(t, newCount, repeatCount, retentionRate(newCount, repeatCount));
                })
                .toList();

        PatientTrendDTO trend = buildPatientTrend(dateFrom, dateTo, appointmentsInRange, firstVisitByPatient);

        return new PatientReportDTO(dateFrom, dateTo, newPatientIds.size(), repeatPatientIds.size(),
                retentionRate(newPatientIds.size(), repeatPatientIds.size()), therapistMetrics, trend);
    }

    private PatientTrendDTO buildPatientTrend(LocalDate dateFrom, LocalDate dateTo,
                                               List<Appointment> appointmentsInRange,
                                               Map<Long, LocalDateTime> firstVisitByPatient) {
        Map<LocalDate, Set<Long>> patientsByDate = new LinkedHashMap<>();
        for (Appointment a : appointmentsInRange) {
            LocalDate day = a.getAppointmentDateTime().toLocalDate();
            patientsByDate.computeIfAbsent(day, k -> new HashSet<>()).add(a.getPatient().getId());
        }

        List<String> labels = new ArrayList<>();
        List<Long> newCounts = new ArrayList<>();
        List<Long> repeatCounts = new ArrayList<>();
        DateTimeFormatter trendLabelFormat = DateTimeFormatter.ofPattern(properties.getReports().getTrendLabelFormat());

        for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
            long newToday = 0;
            long repeatToday = 0;
            for (Long patientId : patientsByDate.getOrDefault(day, Set.of())) {
                LocalDateTime firstVisit = firstVisitByPatient.get(patientId);
                if (firstVisit != null && firstVisit.toLocalDate().equals(day)) {
                    newToday++;
                } else {
                    repeatToday++;
                }
            }
            labels.add(day.format(trendLabelFormat));
            newCounts.add(newToday);
            repeatCounts.add(repeatToday);
        }

        return new PatientTrendDTO(labels, newCounts, repeatCounts);
    }

    private static boolean isNewPatient(LocalDateTime firstVisit, LocalDateTime rangeStart) {
        return firstVisit != null && !firstVisit.isBefore(rangeStart);
    }

    private static double retentionRate(long newCount, long repeatCount) {
        long total = newCount + repeatCount;
        return total == 0 ? 0.0 : (repeatCount * 100.0) / total;
    }

    private List<ServicePerformanceDTO> buildServicePerformance(LocalDate dateFrom, LocalDate dateTo, Long ownTherapistId) {
        LocalDateTime start = dateFrom.atStartOfDay();
        LocalDateTime end = dateTo.atTime(LocalTime.MAX);

        List<ServiceRevenueSummaryDTO> summaries = serviceLineRepository.sumServiceRevenueByServiceAndDateRange(start, end);
        if (summaries.isEmpty()) return List.of();

        List<Long> serviceIds = summaries.stream().map(ServiceRevenueSummaryDTO::serviceId).toList();
        Map<Long, ClinicService> servicesById = clinicServiceRepository.findAllById(serviceIds).stream()
                .collect(Collectors.toMap(ClinicService::getId, s -> s));

        Map<String, String> topTherapistByService = new HashMap<>();
        Map<String, BigDecimal> topRevenueByService = new HashMap<>();
        for (ServiceTherapistRevenueDTO r : serviceLineRepository.sumServiceRevenueByServiceAndTherapist(start, end)) {
            BigDecimal currentTop = topRevenueByService.get(r.serviceName());
            if (currentTop == null || r.revenue().compareTo(currentTop) > 0) {
                topRevenueByService.put(r.serviceName(), r.revenue());
                topTherapistByService.put(r.serviceName(), r.therapistName());
            }
        }

        return summaries.stream()
                .map(s -> {
                    ClinicService service = servicesById.get(s.serviceId());
                    List<String> tags = service != null
                            ? service.getSortedTags().stream().map(Tag::getName).toList()
                            : List.of();
                    BigDecimal averagePrice = s.bookingsCount() == 0
                            ? BigDecimal.ZERO
                            : s.revenue().divide(BigDecimal.valueOf(s.bookingsCount()), 2, RoundingMode.HALF_UP);
                    // A THERAPIST role never sees which named colleague tops a service — that's
                    // another therapist's performance data, not "own row"
                    // (requirements/Security_RBAC_Requirements_v1.md §7).
                    String topTherapistName = ownTherapistId == null ? topTherapistByService.get(s.serviceName()) : null;
                    return new ServicePerformanceDTO(s.serviceName(), tags, s.bookingsCount(), s.revenue(),
                            averagePrice, topTherapistName);
                })
                .sorted(Comparator.comparing(ServicePerformanceDTO::revenue).reversed())
                .toList();
    }

    private List<ProductPerformanceDTO> buildProductPerformance(LocalDate dateFrom, LocalDate dateTo) {
        LocalDateTime start = dateFrom.atStartOfDay();
        LocalDateTime end = dateTo.atTime(LocalTime.MAX);

        List<ProductRevenueSummaryDTO> summaries = productLineRepository.sumProductRevenueByProductAndDateRange(start, end);
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
                    int stockQuantity = product != null ? product.getStockQuantity() : 0;
                    int reorderLevel = product != null ? product.getReorderLevel() : 0;
                    boolean lowStock = product != null && product.isLowStock();
                    return new ProductPerformanceDTO(s.productName(), tags, s.unitsSold(), s.revenue(),
                            stockQuantity, reorderLevel, lowStock);
                })
                .sorted(Comparator.comparing(ProductPerformanceDTO::revenue).reversed())
                .toList();
    }

    private List<TagTherapistRevenueDTO> buildTagTherapistRevenue(LocalDate dateFrom, LocalDate dateTo) {
        LocalDateTime start = dateFrom.atStartOfDay();
        LocalDateTime end = dateTo.atTime(LocalTime.MAX);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        // Keyed by tagName + therapist.id (not name) so two therapists sharing a full name aren't
        // silently merged into one cross-tab row.
        Map<String, Object[]> keyParts = new LinkedHashMap<>();

        for (TagTherapistRevenueDTO r : serviceLineRepository.sumServiceRevenueByTagAndTherapist(start, end)) {
            mergeTagTherapistRevenue(totals, keyParts, r);
        }
        for (TagTherapistRevenueDTO r : productLineRepository.sumProductRevenueByTagAndTherapist(start, end)) {
            mergeTagTherapistRevenue(totals, keyParts, r);
        }

        return totals.entrySet().stream()
                .map(e -> {
                    Object[] parts = keyParts.get(e.getKey());
                    return new TagTherapistRevenueDTO((String) parts[0], (Long) parts[1], (String) parts[2], e.getValue());
                })
                .sorted(Comparator.comparing(TagTherapistRevenueDTO::revenue).reversed())
                .toList();
    }

    private static void mergeTagTherapistRevenue(Map<String, BigDecimal> totals, Map<String, Object[]> keyParts,
                                                  TagTherapistRevenueDTO r) {
        String key = r.tagName() + "|" + r.therapistId();
        totals.merge(key, r.revenue(), BigDecimal::add);
        keyParts.putIfAbsent(key, new Object[]{r.tagName(), r.therapistId(), r.therapistName()});
    }
}
