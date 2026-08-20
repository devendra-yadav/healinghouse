package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary figures for the Expenses list page, computed over whatever filter/date-range is active:
 * a grand total, one line per restricted-visibility category (e.g. "Salaries"/"Commission"), and
 * one combined total for every non-restricted category together.
 */
public record ExpenseSummaryDTO(
        BigDecimal totalAmount,
        List<CategoryTotal> restrictedCategoryTotals,
        BigDecimal nonRestrictedTotal
) {
    public record CategoryTotal(String categoryName, BigDecimal amount) {
    }
}
