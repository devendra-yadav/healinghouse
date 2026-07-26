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

    private List<ProfitLossTrendPointDTO> buildTrend(LocalDate dateFrom, LocalDate dateTo, List<Appointment> completed) {
        Map<LocalDate, BigDecimal> revenueByDay = new HashMap<>();
        for (Appointment a : completed) {
            LocalDate day = a.getAppointmentDateTime().toLocalDate();
            revenueByDay.merge(day, nz(a.getGrandTotal()), BigDecimal::add);
        }

        Map<LocalDate, BigDecimal> expensesByDay = new HashMap<>();
        for (Expense e : expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, dateFrom, dateTo)) {
            expensesByDay.merge(e.getExpenseDate(), nz(e.getAmount()), BigDecimal::add);
        }

        List<ProfitLossTrendPointDTO> trend = new ArrayList<>();
        DateTimeFormatter trendLabelFormat = DateTimeFormatter.ofPattern(properties.getReports().getTrendLabelFormat());
        for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
            BigDecimal revenue = revenueByDay.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal expenses = expensesByDay.getOrDefault(day, BigDecimal.ZERO);
            trend.add(new ProfitLossTrendPointDTO(day.format(trendLabelFormat), revenue, expenses, revenue.subtract(expenses)));
        }
        return trend;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
