package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.RecurrenceFrequency;
import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.RecurringExpenseTemplateRepository;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.persistence.EntityNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private PermissionService permissionService;

    private RecurringExpenseTemplateService service;

    @BeforeEach
    void setUp() {
        service = new RecurringExpenseTemplateService(recurringExpenseTemplateRepository, expenseCategoryRepository, expenseRepository, permissionService);
    }

    private RecurringExpenseTemplate template(LocalDate nextDueDate, LocalDate endDate) {
        return template(nextDueDate, endDate, false);
    }

    private RecurringExpenseTemplate template(LocalDate nextDueDate, LocalDate endDate, boolean restrictedCategory) {
        ExpenseCategory category = ExpenseCategory.builder().id(1L).name("Rent").restrictedVisibility(restrictedCategory).build();
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

    /** Bug_Report_v7.md Finding 11: a template anchored to the 31st must recover to the 31st the
     *  very next month long enough to hold it, rather than staying permanently pinned at whatever
     *  shorter day it first landed on (the old bug: chaining plusMonths off the previous, already-
     *  clamped nextDueDate instead of always re-deriving the day from startDate). */
    @Test
    void generateAdvancesToClampedDayThenRecoversOriginalDayOnceMonthIsLongEnough() {
        LocalDate startDate = LocalDate.of(2026, 1, 31); // 2026 is not a leap year — Feb has 28 days
        RecurringExpenseTemplate t = template(startDate, null);
        when(recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(startDate))
                .thenReturn(List.of(t));

        service.generateDueExpenses(startDate);
        assertThat(t.getNextDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));

        LocalDate secondDueDate = t.getNextDueDate();
        when(recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(secondDueDate))
                .thenReturn(List.of(t));

        service.generateDueExpenses(secondDueDate);
        assertThat(t.getNextDueDate()).isEqualTo(LocalDate.of(2026, 3, 31)); // recovered — March has 31 days
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

    // ── THERAPIST_PLUS restricted-category visibility (§5.5, recurring templates fall under
    //    the same EXPENSES module rule as one-off expense rows) ──

    @Test
    void findAllExcludesRestrictedCategoryTemplatesForTherapistPlus() {
        when(permissionService.currentRole()).thenReturn(AppRole.THERAPIST_PLUS);
        RecurringExpenseTemplate visible = template(LocalDate.now(), null, false);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(recurringExpenseTemplateRepository.findByCategory_RestrictedVisibilityFalse(pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(visible)));

        var result = service.findAll(pageable);

        assertThat(result.getContent()).containsExactly(visible);
    }

    @Test
    void findAllIncludesRestrictedCategoryTemplatesForOwner() {
        when(permissionService.currentRole()).thenReturn(AppRole.OWNER);
        RecurringExpenseTemplate visible = template(LocalDate.now(), null, false);
        RecurringExpenseTemplate hidden = template(LocalDate.now(), null, true);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(recurringExpenseTemplateRepository.findAll(pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(visible, hidden)));

        var result = service.findAll(pageable);

        assertThat(result.getContent()).containsExactly(visible, hidden);
    }

    @Test
    void getByIdHidesRestrictedCategoryTemplateFromTherapistPlus() {
        when(permissionService.currentRole()).thenReturn(AppRole.THERAPIST_PLUS);
        when(recurringExpenseTemplateRepository.findById(1L))
                .thenReturn(java.util.Optional.of(template(LocalDate.now(), null, true)));

        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getByIdAllowsOwnerToSeeRestrictedCategoryTemplate() {
        when(permissionService.currentRole()).thenReturn(AppRole.OWNER);
        RecurringExpenseTemplate t = template(LocalDate.now(), null, true);
        when(recurringExpenseTemplateRepository.findById(1L)).thenReturn(java.util.Optional.of(t));

        assertThat(service.getById(1L)).isSameAs(t);
    }
}
