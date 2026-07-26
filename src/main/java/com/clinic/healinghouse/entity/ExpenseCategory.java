package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Admin-managed master list of expense categories (requirements/Expenses_Requirements_v1.md §3.1)
 * — completely independent of {@code Tag}/{@code ClinicService}/{@code Product}: those describe
 * what the clinic earns from, this describes what it spends on. Same soft-delete (active flag) +
 * permanent-delete-if-unreferenced lifecycle as {@code Combo}.
 */
@Entity
@Table(name = "expense_category", indexes = {
        @Index(name = "idx_expense_category_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** When true, every Expense under this category is hidden from THERAPIST_PLUS sessions
     *  (ExpenseService, §5.5) — filtered at the service layer since visibility here depends on
     *  the category a row belongs to, not just the caller's role. Seeded true only for "Salaries
     *  & Commission" (§5.4); an Owner/Admin can flag any other category confidential the same way. */
    @Builder.Default
    @Column(nullable = false)
    private boolean restrictedVisibility = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
