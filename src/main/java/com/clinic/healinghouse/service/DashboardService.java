package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.DashboardKpiDTO;
import com.clinic.healinghouse.dto.RevenueTrendDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.repository.TherapistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** KPI, trend, and breakdown calculations backing the home dashboard. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private static final DateTimeFormatter TREND_LABEL_FORMAT = DateTimeFormatter.ofPattern("dd MMM");

    private final AppointmentRepository appointmentRepository;
    private final ProductRepository productRepository;
    private final TherapistRepository therapistRepository;
    private final AppointmentServiceLineRepository serviceLineRepository;
    private final AppointmentProductLineRepository productLineRepository;

    public DashboardKpiDTO getTodayKPIs() {
        LocalDate today = LocalDate.now();
        long appointmentsCount = appointmentRepository.countByAppointmentDateTimeBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        BigDecimal revenue = appointmentRepository.sumRevenueByDateRange(
                today.atStartOfDay(), today.atTime(LocalTime.MAX));
        long lowStockCount = productRepository.findLowStockProducts().size();
        long activeTherapistsCount = therapistRepository.countByActiveTrue();

        return new DashboardKpiDTO(appointmentsCount, revenue, lowStockCount, activeTherapistsCount);
    }

    public List<Appointment> getTodayAppointments() {
        LocalDate today = LocalDate.now();
        return appointmentRepository.findTodayAppointments(today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    public List<Product> getLowStockAlerts() {
        return productRepository.findLowStockProducts();
    }

    /** Revenue for each of the last {@code days} days (oldest first), for the trend line chart. */
    public RevenueTrendDTO getRevenueTrend(int days) {
        LocalDate today = LocalDate.now();
        List<String> labels = new ArrayList<>(days);
        List<BigDecimal> values = new ArrayList<>(days);

        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            BigDecimal revenue = appointmentRepository.sumRevenueByDateRange(day.atStartOfDay(), day.atTime(LocalTime.MAX));
            labels.add(day.format(TREND_LABEL_FORMAT));
            values.add(revenue);
        }

        return new RevenueTrendDTO(labels, values);
    }

    /** Services + products revenue attributed to each tag over the range, sorted highest revenue first. */
    public List<TagRevenueDTO> getTagRevenueBreakdown(LocalDate dateFrom, LocalDate dateTo) {
        LocalTime endOfDay = LocalTime.MAX;

        Map<String, BigDecimal> totalsByTag = new LinkedHashMap<>();
        for (TagRevenueDTO r : serviceLineRepository.sumServiceRevenueByTagAndDateRange(dateFrom.atStartOfDay(), dateTo.atTime(endOfDay))) {
            totalsByTag.merge(r.tagName(), r.revenue(), BigDecimal::add);
        }
        for (TagRevenueDTO r : productLineRepository.sumProductRevenueByTagAndDateRange(dateFrom.atStartOfDay(), dateTo.atTime(endOfDay))) {
            totalsByTag.merge(r.tagName(), r.revenue(), BigDecimal::add);
        }

        return totalsByTag.entrySet().stream()
                .map(e -> new TagRevenueDTO(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(TagRevenueDTO::revenue).reversed())
                .toList();
    }
}
