package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ExpenseCategoryForm;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.RecurringExpenseTemplateRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Manages the Expense Category master list (requirements/Expenses_Requirements_v1.md §3.1, §5.1,
 * §5.6) — mirrors ComboService's active/inactive soft-delete + permanent-delete-if-unreferenced
 * lifecycle exactly. Deliberately isolated from Tag/ClinicService/Product (§5.1).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ExpenseCategoryService {

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseRepository expenseRepository;
    private final RecurringExpenseTemplateRepository recurringExpenseTemplateRepository;

    @Transactional(readOnly = true)
    public List<ExpenseCategory> findAllActive() {
        return expenseCategoryRepository.findByActiveTrueOrderByNameAsc();
    }

    /** Paginated, always matches active AND inactive — same reasoning as ComboService.search(query, pageable). */
    @Transactional(readOnly = true)
    public Page<ExpenseCategory> search(String query, Pageable pageable) {
        if (StringUtils.hasText(query)) {
            return expenseCategoryRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim(), pageable);
        }
        return expenseCategoryRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    /** Includes deactivated categories too — backs the list page's "Show inactive" toggle. */
    @Transactional(readOnly = true)
    public Page<ExpenseCategory> findAllIncludingInactive(Pageable pageable) {
        return expenseCategoryRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public ExpenseCategory getById(Long id) {
        return expenseCategoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Expense category not found: " + id));
    }

    public ExpenseCategory save(ExpenseCategoryForm form) {
        // Checked before anything is persisted — same bug this avoids as ComboService.save
        // (a blank @NotBlank name would otherwise surface as a ConstraintViolationException the
        // controller's catch(IllegalArgumentException) can't handle, losing entered data).
        if (!StringUtils.hasText(form.getName())) {
            throw new IllegalArgumentException("Name is required.");
        }
        ExpenseCategory category = form.getId() != null ? getById(form.getId()) : ExpenseCategory.builder().build();
        boolean isNew = category.getId() == null;

        category.setName(form.getName().trim());
        category.setActive(form.isActive());
        category.setRestrictedVisibility(form.isRestrictedVisibility());

        ExpenseCategory saved = expenseCategoryRepository.save(category);
        log.info("{} expense category id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    public void deactivate(Long id) {
        ExpenseCategory category = getById(id);
        category.setActive(false);
        expenseCategoryRepository.save(category);
        log.info("Deactivated expense category id={} name='{}'", category.getId(), category.getName());
    }

    public void activate(Long id) {
        ExpenseCategory category = getById(id);
        category.setActive(true);
        expenseCategoryRepository.save(category);
        log.info("Reactivated expense category id={} name='{}'", category.getId(), category.getName());
    }

    /** Only allowed once deactivated, and only if unreferenced by any Expense or RecurringExpenseTemplate. */
    public void permanentlyDelete(Long id) {
        ExpenseCategory category = getById(id);
        if (category.isActive()) {
            throw new IllegalArgumentException("Deactivate this category before permanently deleting it.");
        }
        if (expenseRepository.existsByCategory_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + category.getName()
                    + "\" — it is used by one or more expenses.");
        }
        if (recurringExpenseTemplateRepository.existsByCategory_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + category.getName()
                    + "\" — it is used by one or more recurring expense templates.");
        }
        expenseCategoryRepository.delete(category);
        log.info("Permanently deleted expense category id={} name='{}'", id, category.getName());
    }
}
