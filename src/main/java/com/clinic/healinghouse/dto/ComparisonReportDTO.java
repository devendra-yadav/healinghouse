package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.util.List;

/** Side-by-side earnings for a selected subset of therapists over a date range. */
public record ComparisonReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<TherapistEarningsDTO> therapistEarnings
) {
}
