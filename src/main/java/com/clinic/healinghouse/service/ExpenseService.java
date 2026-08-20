package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ExpenseFilter;
import com.clinic.healinghouse.dto.ExpenseForm;
import com.clinic.healinghouse.dto.ExpenseSummaryDTO;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final PermissionService permissionService;

    @Transactional(readOnly = true)
    public Page<Expense> search(ExpenseFilter filter, Pageable pageable) {
        return expenseRepository.findAll(buildSpec(filter), pageable);
    }

    /** Grand total + restricted-category breakdown + combined non-restricted total, over every
     *  expense matching the same filter/scoping the list page's table uses (not just the current
     *  page). THERAPIST_PLUS scoping applies here too, so a restricted category never appears. */
    @Transactional(readOnly = true)
    public ExpenseSummaryDTO getSummary(ExpenseFilter filter) {
        List<Expense> expenses = expenseRepository.findAll(buildSpec(filter));
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal nonRestrictedTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> restrictedTotals = new LinkedHashMap<>();
        for (Expense e : expenses) {
            total = total.add(e.getAmount());
            if (e.getCategory().isRestrictedVisibility()) {
                restrictedTotals.merge(e.getCategory().getName(), e.getAmount(), BigDecimal::add);
            } else {
                nonRestrictedTotal = nonRestrictedTotal.add(e.getAmount());
            }
        }
        List<ExpenseSummaryDTO.CategoryTotal> restrictedCategoryTotals = restrictedTotals.entrySet().stream()
                .map(entry -> new ExpenseSummaryDTO.CategoryTotal(entry.getKey(), entry.getValue()))
                .toList();
        return new ExpenseSummaryDTO(total, restrictedCategoryTotals, nonRestrictedTotal);
    }

    private Specification<Expense> buildSpec(ExpenseFilter filter) {
        Specification<Expense> spec = Specification
                .where(ExpenseSpec.hasCategoryId(filter.categoryId()))
                .and(ExpenseSpec.betweenExpenseDates(filter.dateFrom(), filter.dateTo()))
                .and(ExpenseSpec.hasVendorContains(filter.vendorName()))
                .and(ExpenseSpec.hasPaymentMethod(filter.paymentMethod()))
                .and(ExpenseSpec.hasStatus(filter.status()));
        return applyRestrictedCategoryScoping(spec);
    }

    /** When the caller is THERAPIST_PLUS, every expense under a restricted-visibility category
     *  (e.g. "Salaries"/"Commission") is excluded — inline service-layer scoping, not a
     *  PermissionAspect change, since it depends on a joined attribute, not just the role
     *  (requirements/Expenses_Requirements_v1.md §5.5). */
    private Specification<Expense> applyRestrictedCategoryScoping(Specification<Expense> spec) {
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS) {
            return spec.and(ExpenseSpec.categoryNotRestricted());
        }
        return spec;
    }

    /** Throws EntityNotFoundException (not AccessDeniedException) for a THERAPIST_PLUS caller
     *  requesting a restricted-category expense — avoids leaking that row's existence. */
    @Transactional(readOnly = true)
    public Expense getById(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Expense not found: " + id));
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS && expense.getCategory().isRestrictedVisibility()) {
            throw new EntityNotFoundException("Expense not found: " + id);
        }
        return expense;
    }

    public Expense create(ExpenseForm form, User recordedBy) {
        Expense expense = Expense.builder().recordedBy(recordedBy).build();
        applyForm(expense, form);
        Expense saved = expenseRepository.save(expense);
        log.info("Created expense id={} category='{}' amount={}", saved.getId(), saved.getCategory().getName(), saved.getAmount());
        return saved;
    }

    public Expense update(Long id, ExpenseForm form) {
        Expense expense = getById(id);
        if (expense.getStatus() == ExpenseStatus.VOIDED) {
            throw new IllegalArgumentException("A voided expense cannot be edited.");
        }
        applyForm(expense, form);
        Expense saved = expenseRepository.save(expense);
        log.info("Updated expense id={}", saved.getId());
        return saved;
    }

    public void voidExpense(Long id) {
        Expense expense = getById(id);
        if (expense.getStatus() == ExpenseStatus.VOIDED) {
            return;
        }
        expense.setStatus(ExpenseStatus.VOIDED);
        expenseRepository.save(expense);
        log.info("Voided expense id={}", id);
    }

    private void applyForm(Expense expense, ExpenseForm form) {
        if (form.getCategoryId() == null) {
            throw new IllegalArgumentException("Category is required.");
        }
        if (form.getAmount() == null || form.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        if (form.getExpenseDate() == null) {
            throw new IllegalArgumentException("Expense date is required.");
        }
        ExpenseCategory category = expenseCategoryRepository.findById(form.getCategoryId())
                .orElseThrow(() -> new EntityNotFoundException("Expense category not found: " + form.getCategoryId()));
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS && category.isRestrictedVisibility()) {
            // Mirrors getById's existence-masking — a THERAPIST_PLUS caller who crafts a request
            // with a restricted category id directly (bypassing the filtered dropdown) shouldn't
            // learn the category exists.
            throw new EntityNotFoundException("Expense category not found: " + form.getCategoryId());
        }

        expense.setCategory(category);
        expense.setLabel(form.getLabel());
        expense.setExpenseDate(form.getExpenseDate());
        expense.setAmount(form.getAmount());
        expense.setVendorName(form.getVendorName());
        expense.setPaymentMethod(form.getPaymentMethod());
        expense.setNotes(form.getNotes());
    }
}
