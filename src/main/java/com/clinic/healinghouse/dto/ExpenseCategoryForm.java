package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.ExpenseCategory;
import lombok.Data;

@Data
public class ExpenseCategoryForm {

    private Long id;
    private String name;
    private boolean active = true;
    private boolean restrictedVisibility = false;

    public static ExpenseCategoryForm from(ExpenseCategory category) {
        ExpenseCategoryForm form = new ExpenseCategoryForm();
        form.setId(category.getId());
        form.setName(category.getName());
        form.setActive(category.isActive());
        form.setRestrictedVisibility(category.isRestrictedVisibility());
        return form;
    }
}
