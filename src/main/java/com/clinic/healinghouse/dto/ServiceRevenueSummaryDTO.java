package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Internal projection: bookings count and revenue for a single service over a date range. */
public record ServiceRevenueSummaryDTO(
        Long serviceId,
        String serviceName,
        long bookingsCount,
        BigDecimal revenue
) {
}
