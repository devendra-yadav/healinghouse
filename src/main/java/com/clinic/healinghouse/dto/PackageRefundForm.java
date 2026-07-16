package com.clinic.healinghouse.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PackageRefundForm {

    private Long patientPackageId;
    private BigDecimal amount;

    /** Kept as String to avoid Spring's binder error on an empty <select>, same reason as WalletRefundForm. */
    private String paymentMethod;
    private String note;
}
