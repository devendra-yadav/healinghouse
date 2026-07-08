package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Internal projection: units sold and revenue for a single product over a date range. */
public record ProductRevenueSummaryDTO(
        Long productId,
        String productName,
        long unitsSold,
        BigDecimal revenue
) {
}
