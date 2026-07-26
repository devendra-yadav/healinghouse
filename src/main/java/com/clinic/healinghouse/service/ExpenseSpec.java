package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.PaymentMethod;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

/**
 * Reusable JPA Specifications for dynamic Expense list filtering (requirements/Expenses_
 * Requirements_v1.md §4.4, §5.5) — mirrors AppointmentSpec's null-safe cb.conjunction() idiom so
 * every predicate can always be unconditionally .and()-chained regardless of which filters are active.
 */
public class ExpenseSpec {

    private ExpenseSpec() {}

    public static Specification<Expense> hasCategoryId(Long categoryId) {
        return (root, query, cb) ->
                categoryId == null ? cb.conjunction() : cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Expense> betweenExpenseDates(LocalDate start, LocalDate end) {
        return (root, query, cb) -> {
            if (start == null && end == null) return cb.conjunction();
            if (start == null) return cb.lessThanOrEqualTo(root.get("expenseDate"), end);
            if (end == null)   return cb.greaterThanOrEqualTo(root.get("expenseDate"), start);
            return cb.between(root.get("expenseDate"), start, end);
        };
    }

    public static Specification<Expense> hasVendorContains(String text) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(text)) return cb.conjunction();
            String pattern = "%" + text.trim().toLowerCase() + "%";
            return cb.like(cb.lower(root.get("vendorName")), pattern);
        };
    }

    public static Specification<Expense> hasPaymentMethod(PaymentMethod paymentMethod) {
        return (root, query, cb) ->
                paymentMethod == null ? cb.conjunction() : cb.equal(root.get("paymentMethod"), paymentMethod);
    }

    public static Specification<Expense> hasStatus(ExpenseStatus status) {
        return (root, query, cb) ->
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    /** The THERAPIST_PLUS scoping mechanism (§5.5) — excludes every expense whose category is
     *  flagged confidential (e.g. "Salaries & Commission"). Applied inline by ExpenseService,
     *  never by the PermissionAspect, since it depends on a joined attribute, not just the role. */
    public static Specification<Expense> categoryNotRestricted() {
        return (root, query, cb) -> cb.equal(root.get("category").get("restrictedVisibility"), false);
    }
}
