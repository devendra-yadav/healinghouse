package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ExpenseCategoryForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.RecurringExpenseTemplateRepository;
import com.clinic.healinghouse.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** requirements/Expenses_Requirements_v1.md §5.1, §5.6 — soft-delete + permanent-delete guard,
 *  mirroring ComboService's lifecycle. */
@ExtendWith(MockitoExtension.class)
class ExpenseCategoryServiceTests {

    @Mock private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private RecurringExpenseTemplateRepository recurringExpenseTemplateRepository;
    @Mock private PermissionService permissionService;

    private ExpenseCategoryService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseCategoryService(expenseCategoryRepository, expenseRepository, recurringExpenseTemplateRepository, permissionService);
    }

    private ExpenseCategory category(long id, boolean active) {
        return ExpenseCategory.builder().id(id).name("Raw Materials").active(active).build();
    }

    private ExpenseCategory category(long id, String name, boolean restricted) {
        return ExpenseCategory.builder().id(id).name(name).active(true).restrictedVisibility(restricted).build();
    }

    // ── THERAPIST_PLUS restricted-category visibility (§5.5) ──

    @Test
    void findAllActiveVisibleExcludesRestrictedCategoriesForTherapistPlus() {
        when(permissionService.currentRole()).thenReturn(AppRole.THERAPIST_PLUS);
        when(expenseCategoryRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(
                category(1L, "Raw Materials", false),
                category(2L, "Salaries", true)));

        List<ExpenseCategory> visible = service.findAllActiveVisible();

        assertThat(visible).extracting(ExpenseCategory::getName).containsExactly("Raw Materials");
    }

    @Test
    void findAllActiveVisibleIncludesRestrictedCategoriesForOwner() {
        when(permissionService.currentRole()).thenReturn(AppRole.OWNER);
        when(expenseCategoryRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(
                category(1L, "Raw Materials", false),
                category(2L, "Salaries", true)));

        List<ExpenseCategory> visible = service.findAllActiveVisible();

        assertThat(visible).extracting(ExpenseCategory::getName)
                .containsExactly("Raw Materials", "Salaries");
    }

    @Test
    void saveThrowsOnBlankName() {
        ExpenseCategoryForm form = new ExpenseCategoryForm();
        form.setName("   ");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name is required");
        verify(expenseCategoryRepository, never()).save(any());
    }

    @Test
    void permanentlyDeleteThrowsWhileStillActive() {
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, true)));

        assertThatThrownBy(() -> service.permanentlyDelete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Deactivate this category");
        verify(expenseCategoryRepository, never()).delete(any());
    }

    @Test
    void permanentlyDeleteThrowsWhenReferencedByAnExpense() {
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, false)));
        when(expenseRepository.existsByCategory_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.permanentlyDelete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("used by one or more expenses");
        verify(expenseCategoryRepository, never()).delete(any());
    }

    @Test
    void permanentlyDeleteThrowsWhenReferencedByARecurringTemplate() {
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, false)));
        when(expenseRepository.existsByCategory_Id(1L)).thenReturn(false);
        when(recurringExpenseTemplateRepository.existsByCategory_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.permanentlyDelete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recurring expense templates");
        verify(expenseCategoryRepository, never()).delete(any());
    }

    @Test
    void permanentlyDeleteSucceedsWhenDeactivatedAndUnreferenced() {
        ExpenseCategory cat = category(1L, false);
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(expenseRepository.existsByCategory_Id(1L)).thenReturn(false);
        when(recurringExpenseTemplateRepository.existsByCategory_Id(1L)).thenReturn(false);

        service.permanentlyDelete(1L);

        verify(expenseCategoryRepository).delete(cat);
    }

    @Test
    void deactivateFlipsActiveFlag() {
        ExpenseCategory cat = category(1L, true);
        when(expenseCategoryRepository.findById(1L)).thenReturn(Optional.of(cat));
        when(expenseCategoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        assertThat(cat.isActive()).isFalse();
    }
}
