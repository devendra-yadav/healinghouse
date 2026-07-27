package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ExpenseCategoryBreakdownDTO;
import com.clinic.healinghouse.dto.ProfitLossReportDTO;
import com.clinic.healinghouse.dto.ProfitLossTrendPointDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Profit & Loss report (/reports/profit-loss): Net Revenue (same COMPLETED-appointment
 * basis as the Actual Revenue report, so the two reconcile exactly for an identical date range)
 * minus Total Expenses (active, non-voided) = Net Profit. See requirements/Expenses_Requirements_
 * v1.md §6.5, §8, §9.9.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfitLossReportAggregator {

    private final AppointmentRepository appointmentRepository;
    private final ExpenseRepository expenseRepository;
    private final HealingHouseProperties properties;

    public ProfitLossReportDTO getProfitLossReport(LocalDate dateFrom, LocalDate dateTo) {
        Specification<Appointment> completedSpec = Specification
                .where(AppointmentSpec.betweenDates(dateFrom.atStartOfDay(), dateTo.atTime(LocalTime.MAX)))
                .and(AppointmentSpec.hasStatus(AppointmentStatus.COMPLETED));
        List<Appointment> completed = appointmentRepository.findAll(completedSpec);

        BigDecimal netRevenue = BigDecimal.ZERO;
        for (Appointment a : completed) {
            netRevenue = netRevenue.add(nz(a.getGrandTotal()));
        }

        BigDecimal totalExpenses = expenseRepository.sumAmountByStatusAndDateRange(ExpenseStatus.ACTIVE, dateFrom, dateTo);
        BigDecimal netProfit = netRevenue.subtract(totalExpenses);

        List<ExpenseCategoryBreakdownDTO> expensesByCategory = expenseRepository
                .sumAmountByCategoryInRange(ExpenseStatus.ACTIVE, dateFrom, dateTo).stream()
                .map(row -> new ExpenseCategoryBreakdownDTO((String) row[0], (BigDecimal) row[1]))
                .toList();

        List<ProfitLossTrendPointDTO> trend = buildTrend(dateFrom, dateTo, completed);

        return new ProfitLossReportDTO(dateFrom, dateTo, netRevenue, totalExpenses, netProfit, expensesByCategory, trend);
    }

    /**
     * Buckets by calendar month (requirements/Expenses_Requirements_v1.md §4.5/§8: "month
     * buckets"/"month-over-month trend"), not by day — a day-level bucket produced one point per
     * day (365 for a full-year range), contradicting the documented aggregation grain and making
     * the Chart.js trend chart illegible (Bug_Report_v6.md Finding 8). Uses its own fixed "MMM
     * yyyy" label format rather than the shared HealingHouseProperties.Reports.trendLabelFormat,
     * which is a day-granularity format ("dd MMM") also used by the daily trends on the Dashboard/
     * standard reports/Actual Revenue report — repurposing it here would need it to mean two
     * different things depending on which page reads it.
     */
    private List<ProfitLossTrendPointDTO> buildTrend(LocalDate dateFrom, LocalDate dateTo, List<Appointment> completed) {
        Map<YearMonth, BigDecimal> revenueByMonth = new HashMap<>();
        for (Appointment a : completed) {
            YearMonth month = YearMonth.from(a.getAppointmentDateTime().toLocalDate());
            revenueByMonth.merge(month, nz(a.getGrandTotal()), BigDecimal::add);
        }

        Map<YearMonth, BigDecimal> expensesByMonth = new HashMap<>();
        for (Expense e : expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, dateFrom, dateTo)) {
            expensesByMonth.merge(YearMonth.from(e.getExpenseDate()), nz(e.getAmount()), BigDecimal::add);
        }

        List<ProfitLossTrendPointDTO> trend = new ArrayList<>();
        DateTimeFormatter monthLabelFormat = DateTimeFormatter.ofPattern("MMM yyyy");
        YearMonth lastMonth = YearMonth.from(dateTo);
        for (YearMonth month = YearMonth.from(dateFrom); !month.isAfter(lastMonth); month = month.plusMonths(1)) {
            BigDecimal revenue = revenueByMonth.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal expenses = expensesByMonth.getOrDefault(month, BigDecimal.ZERO);
            trend.add(new ProfitLossTrendPointDTO(month.format(monthLabelFormat), revenue, expenses, revenue.subtract(expenses)));
        }
        return trend;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
