package com.clinic.healinghouse.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WalletRefundForm {
    private Long patientId;
    private BigDecimal amount;
    private String paymentMethod;   // kept as String to avoid enum binding errors on empty selection
    private String note;
    private String returnUrl;
}
