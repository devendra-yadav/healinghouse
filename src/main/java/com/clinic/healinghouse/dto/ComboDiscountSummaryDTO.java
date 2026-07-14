package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Internal projection: total combo discount for a single appointment, used to avoid lazily loading each appointment's combos collection. */
public record ComboDiscountSummaryDTO(
        Long appointmentId,
        BigDecimal discountAmount
) {
}
