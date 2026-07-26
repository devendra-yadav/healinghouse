package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Today's KPI numbers for the dashboard cards row. monthExpenses/monthNetProfit are calendar-month-
 *  to-date figures (requirements/Expenses_Requirements_v1.md §6.6, §8) — gated in the template by
 *  REPORTS_PROFIT_LOSS/VIEW, same as every other financial KPI card. */
public record DashboardKpiDTO(
        long todayAppointmentsCount,
        BigDecimal todayRevenue,
        long lowStockCount,
        long activeTherapistsCount,
        BigDecimal monthExpenses,
        BigDecimal monthNetProfit
) {
}
