package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseListRowDTO(
        Long id,
        LocalDate expenseDate,
        String label,
        String categoryName,
        BigDecimal amount,
        String vendorName,
        PaymentMethod paymentMethod,
        ExpenseStatus status,
        String recordedByUsername,
        boolean recurring
) {
    public static ExpenseListRowDTO from(Expense e) {
        return new ExpenseListRowDTO(
                e.getId(),
                e.getExpenseDate(),
                e.getLabel(),
                e.getCategory().getName(),
                e.getAmount(),
                e.getVendorName(),
                e.getPaymentMethod(),
                e.getStatus(),
                e.getRecordedBy() != null ? e.getRecordedBy().getUsername() : null,
                e.getSourceTemplate() != null
        );
    }
}
