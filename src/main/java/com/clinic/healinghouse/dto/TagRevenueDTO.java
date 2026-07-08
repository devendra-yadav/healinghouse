package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Revenue (services + products) attributed to a single tag over a date range. */
public record TagRevenueDTO(
        String tagName,
        BigDecimal revenue
) {
}
