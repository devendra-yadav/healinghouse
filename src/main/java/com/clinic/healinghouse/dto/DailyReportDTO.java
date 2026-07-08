package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.util.List;

/** Single-day report: clinic-wide summary plus per-therapist earnings for that day. */
public record DailyReportDTO(
        LocalDate date,
        PeriodSummaryDTO summary,
        List<TherapistEarningsDTO> therapistEarnings
) {
}
