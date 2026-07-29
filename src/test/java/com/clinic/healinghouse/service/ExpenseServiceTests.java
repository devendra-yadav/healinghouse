package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ExpenseForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** requirements/Expenses_Requirements_v1.md §5.2 (soft-void immutability) and §5.5 (THERAPIST_PLUS
 *  restricted-category scoping). */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTests {

    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private PermissionService permissionService;

    private ExpenseService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseService(expenseRepository, expenseCategoryRepository, permissionService);
    }

    private ExpenseCategory category(long id, boolean restricted) {
        return ExpenseCategory.builder().id(id).name("Raw Materials").active(true).restrictedVisibility(restricted).build();
    }

    private Expense expense(long id, ExpenseStatus status, ExpenseCategory category) {
        return Expense.builder().id(id).status(status).category(category)
                .amount(BigDecimal.TEN).expenseDate(LocalDate.now()).build();
    }

    private ExpenseForm form(Long categoryId, BigDecimal amount, LocalDate date) {
        ExpenseForm f = new ExpenseForm();
        f.setCategoryId(categoryId);
        f.setAmount(amount);
        f.setExpenseDate(date);
        return f;
    }

    // ── Soft-void lifecycle ──

    @Test
    void updateThrowsWhenExpenseAlreadyVoided() {
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(expense(1L, ExpenseStatus.VOIDED, category(1L, false))));

        assertThatThrownBy(() -> service.update(1L, form(1L, BigDecimal.TEN, LocalDate.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("voided expense cannot be edited");
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void voidExpenseIsIdempotent() {
        Expense e = expense(1L, ExpenseStatus.VOIDED, category(1L, false));
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(e));

        service.voidExpense(1L);

        verify(expenseRepository, never()).save(any());
    }

    @Test
    void voidExpenseSetsStatusToVoided() {
        Expense e = expense(1L, ExpenseStatus.ACTIVE, category(1L, false));
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(e));
        when(expenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.voidExpense(1L);

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.VOIDED);
    }

    // ── THERAPIST_PLUS restricted-category visibility (§5.5) ──

    @Test
    void getByIdHidesRestrictedCategoryExpenseFromTherapistPlus() {
        when(permissionService.currentRole()).thenReturn(AppRole.THERAPIST_PLUS);
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(expense(1L, ExpenseStatus.ACTIVE, category(1L, true))));

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getByIdAllowsOwnerToSeeRestrictedCategoryExpense() {
        when(permissionService.currentRole()).thenReturn(AppRole.OWNER);
        Expense e = expense(1L, ExpenseStatus.ACTIVE, category(1L, true));
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(e));

        assertThat(service.getById(1L)).isSameAs(e);
    }

    @Test
    void getByIdAllowsTherapistPlusToSeeNonRestrictedCategoryExpense() {
        when(permissionService.currentRole()).thenReturn(AppRole.THERAPIST_PLUS);
        Expense e = expense(1L, ExpenseStatus.ACTIVE, category(1L, false));
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(e));

        assertThat(service.getById(1L)).isSameAs(e);
    }

    // ── create() validation ──

    @Test
    void createThrowsWhenAmountIsZeroOrNegative() {
        User recordedBy = User.builder().id(1L).username("owner").build();

        assertThatThrownBy(() -> service.create(form(1L, BigDecimal.ZERO, LocalDate.now()), recordedBy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");
    }

    @Test
    void createThrowsWhenCategoryMissing() {
        User recordedBy = User.builder().id(1L).username("owner").build();

        assertThatThrownBy(() -> service.create(form(null, BigDecimal.TEN, LocalDate.now()), recordedBy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Category is required");
    }

    @Test
    void createSucceedsWithValidForm() {
        User recordedBy = User.builder().id(1L).username("owner").build();
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, false)));
        when(expenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Expense saved = service.create(form(1L, BigDecimal.valueOf(250), LocalDate.now()), recordedBy);

        assertThat(saved.getRecordedBy()).isEqualTo(recordedBy);
        assertThat(saved.getStatus()).isEqualTo(ExpenseStatus.ACTIVE);
        assertThat(saved.getAmount()).isEqualByComparingTo("250");
    }
}
