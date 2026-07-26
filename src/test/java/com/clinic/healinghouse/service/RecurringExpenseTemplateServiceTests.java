package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.RecurrenceFrequency;
import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.RecurringExpenseTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** requirements/Expenses_Requirements_v1.md §5.3 — recurring generation must advance nextDueDate
 *  exactly once per generation (idempotent re-run) and auto-deactivate once past endDate. */
@ExtendWith(MockitoExtension.class)
class RecurringExpenseTemplateServiceTests {

    @Mock private RecurringExpenseTemplateRepository recurringExpenseTemplateRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private ExpenseRepository expenseRepository;

    private RecurringExpenseTemplateService service;

    @BeforeEach
    void setUp() {
        service = new RecurringExpenseTemplateService(recurringExpenseTemplateRepository, expenseCategoryRepository, expenseRepository);
    }

    private RecurringExpenseTemplate template(LocalDate nextDueDate, LocalDate endDate) {
        ExpenseCategory category = ExpenseCategory.builder().id(1L).name("Rent").build();
        User createdBy = User.builder().id(1L).username("owner").build();
        return RecurringExpenseTemplate.builder()
                .id(1L).category(category).label("Monthly Rent")
                .defaultAmount(BigDecimal.valueOf(15000))
                .frequency(RecurrenceFrequency.MONTHLY)
                .startDate(nextDueDate).nextDueDate(nextDueDate).endDate(endDate)
                .active(true).createdBy(createdBy)
                .build();
    }

    @Test
    void generateDueExpensesCreatesOneExpenseAndAdvancesNextDueDateByOneMonth() {
        LocalDate today = LocalDate.of(2026, 7, 1);
        RecurringExpenseTemplate t = template(today, null);
        when(recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(today))
                .thenReturn(List.of(t));

        int generated = service.generateDueExpenses(today);

        assertThat(generated).isEqualTo(1);
        assertThat(t.getNextDueDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(t.isActive()).isTrue();

        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(captor.capture());
        Expense saved = captor.getValue();
        assertThat(saved.getAmount()).isEqualByComparingTo("15000");
        assertThat(saved.getExpenseDate()).isEqualTo(today);
        assertThat(saved.getSourceTemplate()).isEqualTo(t);
        assertThat(saved.getRecordedBy()).isEqualTo(t.getCreatedBy());
    }

    @Test
    void generateDueExpensesIsANoOpWhenNothingIsDue() {
        when(recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(any())).thenReturn(List.of());

        int generated = service.generateDueExpenses(LocalDate.now());

        assertThat(generated).isZero();
        verify(expenseRepository, times(0)).save(any());
    }

    @Test
    void generateAutoDeactivatesTemplateOncePastEndDate() {
        LocalDate today = LocalDate.of(2026, 7, 1);
        RecurringExpenseTemplate t = template(today, LocalDate.of(2026, 7, 15)); // ends before next month
        when(recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(today))
                .thenReturn(List.of(t));

        service.generateDueExpenses(today);

        assertThat(t.getNextDueDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(t.isActive()).isFalse();
    }

    @Test
    void generateNowIsANoOpForAnInactiveTemplate() {
        RecurringExpenseTemplate t = template(LocalDate.now(), null);
        t.setActive(false);
        when(recurringExpenseTemplateRepository.findById(1L)).thenReturn(java.util.Optional.of(t));

        int generated = service.generateNow(1L);

        assertThat(generated).isZero();
        verify(expenseRepository, times(0)).save(any());
    }
}
