package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.PatientHistorySummary;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Computes lifetime aggregate stats for a patient's Patient Detail page (Section B). */
@Service
@RequiredArgsConstructor
public class PatientHistoryService {

    private final AppointmentRepository appointmentRepository;

    @Transactional(readOnly = true)
    public PatientHistorySummary summarize(Patient patient) {
        List<Appointment> all = appointmentRepository.findByPatientOrderByAppointmentDateTimeDesc(patient);

        PatientHistorySummary.PatientHistorySummaryBuilder summary = PatientHistorySummary.builder()
                .totalAppointments(all.size());

        if (all.isEmpty()) {
            return summary.build();
        }

        int completedCount = 0, cancelledCount = 0, noShowCount = 0;
        for (Appointment a : all) {
            switch (a.getStatus()) {
                case COMPLETED -> completedCount++;
                case CANCELLED -> cancelledCount++;
                case NO_SHOW   -> noShowCount++;
                default -> { /* SCHEDULED — not counted in any bucket */ }
            }
        }
        summary.completedCount(completedCount)
               .cancelledCount(cancelledCount)
               .noShowCount(noShowCount);

        List<Appointment> completed = all.stream()
                .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
                .toList();

        summary.totalRevenue(sumOf(completed, Appointment::getGrandTotal))
               .totalPaid(sumOf(completed, Appointment::getAmountPaid))
               .totalOutstanding(sumOf(completed, Appointment::getBalanceDue));

        completed.stream()
                .max(Comparator.comparing(Appointment::getAppointmentDateTime))
                .ifPresent(a -> summary.lastVisitDate(a.getAppointmentDateTime()));

        // Most-seen therapist — counted per line item (who actually performed/sold each
        // line), not per appointment, since a line's therapist can differ from the
        // appointment's main therapist.
        Map<Long, Integer> therapistCounts = new HashMap<>();
        Map<Long, String>  therapistNames  = new HashMap<>();

        // Top service / product — counted by quantity across all appointments' line items.
        Map<String, Integer> serviceCounts = new HashMap<>();
        Map<String, Integer> productCounts = new HashMap<>();
        for (Appointment a : all) {
            for (AppointmentServiceLine sl : a.getServiceLines()) {
                serviceCounts.merge(sl.getService().getName(), sl.getQuantity(), Integer::sum);
                Therapist t = sl.getTherapist();
                therapistCounts.merge(t.getId(), 1, Integer::sum);
                therapistNames.putIfAbsent(t.getId(), t.getFullName());
            }
            for (AppointmentProductLine pl : a.getProductLines()) {
                productCounts.merge(pl.getProduct().getName(), pl.getQuantity(), Integer::sum);
                Therapist t = pl.getTherapist();
                therapistCounts.merge(t.getId(), 1, Integer::sum);
                therapistNames.putIfAbsent(t.getId(), t.getFullName());
            }
        }
        topEntry(therapistCounts).ifPresent(e ->
                summary.mostSeenTherapistName(therapistNames.get(e.getKey()))
                       .mostSeenTherapistCount(e.getValue()));
        topEntryByName(serviceCounts).ifPresent(e ->
                summary.topServiceName(e.getKey()).topServiceCount(e.getValue()));
        topEntryByName(productCounts).ifPresent(e ->
                summary.topProductName(e.getKey()).topProductCount(e.getValue()));

        // Calendar-aligned period spend (completed appointments only).
        LocalDate today = LocalDate.now();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime yearStart  = today.withDayOfYear(1).atStartOfDay();

        summary.currentMonthSpend(sumOf(
                completed.stream().filter(a -> !a.getAppointmentDateTime().isBefore(monthStart)).toList(),
                Appointment::getGrandTotal));
        summary.currentYearSpend(sumOf(
                completed.stream().filter(a -> !a.getAppointmentDateTime().isBefore(yearStart)).toList(),
                Appointment::getGrandTotal));

        return summary.build();
    }

    private BigDecimal sumOf(List<Appointment> list, Function<Appointment, BigDecimal> extractor) {
        return list.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private java.util.Optional<Map.Entry<Long, Integer>> topEntry(Map<Long, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue());
    }

    private java.util.Optional<Map.Entry<String, Integer>> topEntryByName(Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue());
    }
}
