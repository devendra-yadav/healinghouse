package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.PaymentMethod;

import java.time.LocalDate;

public record ExpenseFilter(
        LocalDate dateFrom,
        LocalDate dateTo,
        Long categoryId,
        String vendorName,
        PaymentMethod paymentMethod,
        ExpenseStatus status
) {
}
