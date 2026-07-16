package com.clinic.healinghouse.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class PackageSaleForm {

    private Long patientId;

    /** Nullable — set if starting from a template; informational only, see PatientPackage.sourceTemplate. */
    private Long sourceTemplateId;

    private String name;
    private List<PackageSaleItemForm> serviceItems = new ArrayList<>();
    private List<PackageSaleItemForm> productItems = new ArrayList<>();

    private BigDecimal totalPrice;
    private LocalDate expiryDate;

    /** Kept as String to avoid Spring's binder error on an empty <select>, same reason as WalletTopUpForm. */
    private String paymentMethod;
    private String note;

    @Data
    public static class PackageSaleItemForm {
        private Long itemId;
        private int sessionCount = 1;
    }
}
