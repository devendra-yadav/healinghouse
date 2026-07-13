package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Post-discount (effective) revenue for a single service or product — used for both the services and products breakdown tables. */
public record RevenueByCatalogItemDTO(
        String name,
        List<String> tags,
        long bookingsCount,
        BigDecimal netRevenue
) {
}
