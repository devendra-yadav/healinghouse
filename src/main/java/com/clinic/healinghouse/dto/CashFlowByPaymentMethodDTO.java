package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

public record CashFlowByPaymentMethodDTO(String label, BigDecimal amount) {
}
