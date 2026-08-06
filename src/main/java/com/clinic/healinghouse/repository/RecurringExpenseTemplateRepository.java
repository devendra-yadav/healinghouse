package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecurringExpenseTemplateRepository extends JpaRepository<RecurringExpenseTemplate, Long> {

    List<RecurringExpenseTemplate> findByActiveTrueOrderByLabelAsc();

    List<RecurringExpenseTemplate> findByActiveTrueAndNextDueDateLessThanEqual(LocalDate asOf);

    // Backs the paginated list page's THERAPIST_PLUS scoping (restricted-category rows excluded at
    // the DB level so Page totals/counts stay accurate — see ExpenseSpec.categoryNotRestricted for
    // the equivalent on the one-off Expense list).
    Page<RecurringExpenseTemplate> findByCategory_RestrictedVisibilityFalse(Pageable pageable);

    // Blocks permanent deletion of an ExpenseCategory still referenced by a recurring template (§5.6).
    boolean existsByCategory_Id(Long categoryId);
}
