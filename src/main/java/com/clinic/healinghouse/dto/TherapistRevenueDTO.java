package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Internal projection: revenue attributed to a single therapist (by line-level therapist, not just main), scoped to an appointment id set. */
public record TherapistRevenueDTO(
        String therapistName,
        BigDecimal revenue
) {
}
