package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ExpenseForm {

    private Long id;
    private Long categoryId;
    private LocalDate expenseDate = LocalDate.now();
    private BigDecimal amount;
    private String vendorName;
    private PaymentMethod paymentMethod;
    private Long therapistId;
    private String notes;

    public static ExpenseForm from(Expense expense) {
        ExpenseForm form = new ExpenseForm();
        form.setId(expense.getId());
        form.setCategoryId(expense.getCategory().getId());
        form.setExpenseDate(expense.getExpenseDate());
        form.setAmount(expense.getAmount());
        form.setVendorName(expense.getVendorName());
        form.setPaymentMethod(expense.getPaymentMethod());
        form.setTherapistId(expense.getTherapist() != null ? expense.getTherapist().getId() : null);
        form.setNotes(expense.getNotes());
        return form;
    }
}
