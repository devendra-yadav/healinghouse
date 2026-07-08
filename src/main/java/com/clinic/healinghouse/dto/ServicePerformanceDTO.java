package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Booking count, revenue, and top-performing therapist for a single service over a date range. */
public record ServicePerformanceDTO(
        String serviceName,
        List<String> tags,
        long bookingsCount,
        BigDecimal revenue,
        BigDecimal averagePrice,
        String topTherapistName
) {
}
