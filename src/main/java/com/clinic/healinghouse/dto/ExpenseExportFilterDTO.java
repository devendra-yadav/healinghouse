package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.PaymentMethod;

/** The active filter selection on the Expenses list page, threaded into CSV/PDF exports so the
 *  downloaded file records exactly what was on-screen when it was generated. */
public record ExpenseExportFilterDTO(
        String categoryName,
        String vendorName,
        PaymentMethod paymentMethod,
        boolean showVoided
) {
}
