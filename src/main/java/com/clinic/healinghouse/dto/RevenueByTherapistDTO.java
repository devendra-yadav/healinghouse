package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Gross vs net (post-discount) revenue attributed to a therapist, main or line-level — distinct from commission. */
public record RevenueByTherapistDTO(
        Long therapistId,
        String therapistName,
        BigDecimal grossRevenue,
        BigDecimal discountAmount,
        BigDecimal netRevenue
) {
}
