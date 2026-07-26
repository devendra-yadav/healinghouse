package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A recurring cost (rent, salary, utility bill) that auto-generates an actual {@link Expense} row
 * each period (requirements/Expenses_Requirements_v1.md §3.3, §5.3) — the amount generated is a
 * starting point only, freely editable afterward if the real bill differs.
 */
@Entity
@Table(name = "recurring_expense_template", indexes = {
        @Index(name = "idx_recurring_expense_template_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringExpenseTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_category_id", nullable = false)
    private ExpenseCategory category;

    @NotBlank
    @Column(nullable = false)
    private String label;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultAmount;

    private String vendorName;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    // @JdbcTypeCode(VARCHAR) — see RolePermission.role javadoc: keeps this a plain VARCHAR so a
    // future 4th RecurrenceFrequency value never hits the native-MySQL-ENUM truncation trap.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private RecurrenceFrequency frequency;

    @Column(nullable = false)
    private LocalDate startDate;

    /** Null = runs indefinitely. Once passed, the template auto-deactivates (§5.3). */
    private LocalDate endDate;

    /** Advanced by {@code frequency} after each generation — the scheduler's cursor. */
    @Column(nullable = false)
    private LocalDate nextDueDate;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
