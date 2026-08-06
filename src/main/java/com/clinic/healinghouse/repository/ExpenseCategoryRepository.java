package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.ExpenseCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    List<ExpenseCategory> findByActiveTrueOrderByNameAsc();

    Page<ExpenseCategory> findByActiveTrueOrderByNameAsc(Pageable pageable);

    // Active-agnostic variant — list-page search always matches active AND inactive.
    Page<ExpenseCategory> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);
}
