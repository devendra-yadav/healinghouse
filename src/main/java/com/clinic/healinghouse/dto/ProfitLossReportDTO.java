package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProfitLossReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal netRevenue,
        BigDecimal totalExpenses,
        BigDecimal netProfit,
        List<ExpenseCategoryBreakdownDTO> expensesByCategory,
        List<ProfitLossTrendPointDTO> trend
) {
}
