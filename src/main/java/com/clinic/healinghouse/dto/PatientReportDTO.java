package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Patient acquisition/retention report for a date range. A patient is "new" if their
 * earliest-ever appointment (any status) falls within [dateFrom, dateTo]; otherwise "repeat".
 */
public record PatientReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        long totalNewPatients,
        long totalRepeatPatients,
        double overallRetentionRate,
        List<TherapistPatientMetricsDTO> therapistMetrics,
        PatientTrendDTO trend
) {
}
