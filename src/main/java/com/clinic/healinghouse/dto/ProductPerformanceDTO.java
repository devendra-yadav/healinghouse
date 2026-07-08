package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Units sold, revenue, and stock status for a single product over a date range. */
public record ProductPerformanceDTO(
        String productName,
        List<String> tags,
        long unitsSold,
        BigDecimal revenue,
        int stockQuantity,
        int reorderLevel,
        boolean lowStock
) {
}
