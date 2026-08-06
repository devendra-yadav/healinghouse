package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long>, JpaSpecificationExecutor<Expense> {

    // Blocks permanent deletion of an ExpenseCategory still referenced by any expense (§5.6).
    boolean existsByCategory_Id(Long categoryId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e " +
           "WHERE e.status = :status AND e.expenseDate BETWEEN :dateFrom AND :dateTo")
    BigDecimal sumAmountByStatusAndDateRange(@Param("status") ExpenseStatus status,
                                              @Param("dateFrom") LocalDate dateFrom,
                                              @Param("dateTo") LocalDate dateTo);

    @Query("SELECT e.category.name, COALESCE(SUM(e.amount), 0) FROM Expense e " +
           "WHERE e.status = :status AND e.expenseDate BETWEEN :dateFrom AND :dateTo " +
           "GROUP BY e.category.name ORDER BY e.category.name")
    List<Object[]> sumAmountByCategoryInRange(@Param("status") ExpenseStatus status,
                                               @Param("dateFrom") LocalDate dateFrom,
                                               @Param("dateTo") LocalDate dateTo);

    List<Expense> findByStatusAndExpenseDateBetween(ExpenseStatus status, LocalDate dateFrom, LocalDate dateTo);

    Page<Expense> findByStatus(ExpenseStatus status, Pageable pageable);
}
