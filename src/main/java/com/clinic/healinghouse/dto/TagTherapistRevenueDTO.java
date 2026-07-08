package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Revenue (services + products) attributed to a single tag x therapist pair over a date range. */
public record TagTherapistRevenueDTO(
        String tagName,
        String therapistName,
        BigDecimal revenue
) {
}
