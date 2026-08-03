package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.RecurrenceFrequency;
import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecurringExpenseTemplateForm {

    private Long id;
    private Long categoryId;
    private String label;
    private BigDecimal defaultAmount;
    private String vendorName;
    private PaymentMethod paymentMethod;
    private RecurrenceFrequency frequency;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate = LocalDate.now();
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    private boolean active = true;

    public static RecurringExpenseTemplateForm from(RecurringExpenseTemplate t) {
        RecurringExpenseTemplateForm form = new RecurringExpenseTemplateForm();
        form.setId(t.getId());
        form.setCategoryId(t.getCategory().getId());
        form.setLabel(t.getLabel());
        form.setDefaultAmount(t.getDefaultAmount());
        form.setVendorName(t.getVendorName());
        form.setPaymentMethod(t.getPaymentMethod());
        form.setFrequency(t.getFrequency());
        form.setStartDate(t.getStartDate());
        form.setEndDate(t.getEndDate());
        form.setActive(t.isActive());
        return form;
    }
}
