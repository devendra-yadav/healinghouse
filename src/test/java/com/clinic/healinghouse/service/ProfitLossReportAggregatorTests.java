package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ProfitLossReportDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** requirements/Expenses_Requirements_v1.md §6.5, §9.9 — Net Revenue must use the same COMPLETED-
 *  appointment basis as the Actual Revenue report, so Net Profit = Net Revenue - Total Expenses holds. */
@ExtendWith(MockitoExtension.class)
class ProfitLossReportAggregatorTests {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private ExpenseRepository expenseRepository;

    private ProfitLossReportAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ProfitLossReportAggregator(appointmentRepository, expenseRepository, new HealingHouseProperties());
    }

    @Test
    void netProfitIsNetRevenueMinusTotalExpenses() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        Appointment completed = Appointment.builder()
                .id(1L)
                .appointmentDateTime(LocalDateTime.of(2026, 7, 10, 10, 0))
                .status(AppointmentStatus.COMPLETED)
                .grandTotal(BigDecimal.valueOf(10000))
                .build();

        when(appointmentRepository.findAll(any(Specification.class))).thenReturn(List.of(completed));
        when(expenseRepository.sumAmountByStatusAndDateRange(ExpenseStatus.ACTIVE, from, to))
                .thenReturn(BigDecimal.valueOf(3000));
        when(expenseRepository.sumAmountByCategoryInRange(ExpenseStatus.ACTIVE, from, to)).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, from, to)).thenReturn(List.of());

        ProfitLossReportDTO report = aggregator.getProfitLossReport(from, to);

        assertThat(report.netRevenue()).isEqualByComparingTo("10000");
        assertThat(report.totalExpenses()).isEqualByComparingTo("3000");
        assertThat(report.netProfit()).isEqualByComparingTo("7000");
    }

    @Test
    void netProfitCanBeNegativeWhenExpensesExceedRevenue() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        when(appointmentRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(expenseRepository.sumAmountByStatusAndDateRange(ExpenseStatus.ACTIVE, from, to))
                .thenReturn(BigDecimal.valueOf(500));
        when(expenseRepository.sumAmountByCategoryInRange(ExpenseStatus.ACTIVE, from, to)).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, from, to)).thenReturn(List.of());

        ProfitLossReportDTO report = aggregator.getProfitLossReport(from, to);

        assertThat(report.netRevenue()).isEqualByComparingTo("0");
        assertThat(report.netProfit()).isEqualByComparingTo("-500");
        assertThat(report.netProfit().signum()).isLessThan(0);
    }
}
