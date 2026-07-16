package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Internal projection: revenue for a single service x therapist pair, used to find each service's top therapist. */
public record ServiceTherapistRevenueDTO(
        String serviceName,
        Long therapistId,
        String therapistName,
        BigDecimal revenue
) {
}
