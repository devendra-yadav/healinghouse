package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Collected amount grouped by payment method — includes a synthetic "Wallet" row alongside the real PaymentMethod values. */
public record RevenueByPaymentMethodDTO(
        String label,
        BigDecimal amount
) {
}
