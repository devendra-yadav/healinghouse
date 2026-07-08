package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Therapist;

/** New vs. repeat patient counts attributed to a therapist's appointments over a date range. */
public record TherapistPatientMetricsDTO(
        Therapist therapist,
        long newPatients,
        long repeatPatients,
        double retentionRate
) {
}
