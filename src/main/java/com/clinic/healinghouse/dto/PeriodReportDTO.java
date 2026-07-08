package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.util.List;

/** Date-range report: clinic-wide summary, per-therapist earnings, tag revenue, and product performance. */
public record PeriodReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        PeriodSummaryDTO summary,
        List<TherapistEarningsDTO> therapistEarnings,
        List<TagRevenueDTO> tagRevenue,
        List<ProductPerformanceDTO> productPerformance
) {
}
