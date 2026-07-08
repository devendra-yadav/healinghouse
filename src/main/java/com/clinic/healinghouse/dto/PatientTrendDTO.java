package com.clinic.healinghouse.dto;

import java.util.List;

/** New vs. repeat patient counts per day across a date range, for the acquisition trend chart. */
public record PatientTrendDTO(
        List<String> labels,
        List<Long> newPatients,
        List<Long> repeatPatients
) {
}
