package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface RecurringExpenseTemplateRepository extends JpaRepository<RecurringExpenseTemplate, Long> {

    List<RecurringExpenseTemplate> findByActiveTrueOrderByLabelAsc();

    List<RecurringExpenseTemplate> findByActiveTrueAndNextDueDateLessThanEqual(LocalDate asOf);

    // Blocks permanent deletion of an ExpenseCategory still referenced by a recurring template (§5.6).
    boolean existsByCategory_Id(Long categoryId);
}
