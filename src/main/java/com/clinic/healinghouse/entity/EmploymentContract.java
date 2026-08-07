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
 * A therapist's employment contract (requirements/Employment_Contracts_Requirements_v1.md §3.1).
 * DRAFT content ({@code contractBodyHtml}) is freely owner-editable; once APPROVED every term field
 * and the body are locked, {@code pdfContent} is rendered, and the therapist's payroll fields
 * (commissionRate/fixedMonthlySalary/performanceBonus*) are synced from this contract's terms (§5.4).
 */
@Entity
@Table(name = "employment_contract", indexes = {
        @Index(name = "idx_employment_contract_therapist", columnList = "therapist_id"),
        @Index(name = "idx_employment_contract_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmploymentContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "therapist_id", nullable = false)
    private Therapist therapist;

    /** e.g. "HHC-2026-0007" — assigned once, at draft creation (§5.6). */
    @Column(nullable = false, unique = true)
    private String contractNumber;

    // @JdbcTypeCode(VARCHAR) — same reasoning as Expense.status: prevents this column from becoming
    // a native MySQL ENUM that can't widen if ContractStatus ever gains a value.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Builder.Default
    @Column(nullable = false)
    private ContractStatus status = ContractStatus.DRAFT;

    // ---- Contract terms (EmploymentContractForm, §4.1) ----

    @Column(nullable = false)
    private LocalDate joiningDate;

    /** Null = open-ended/ongoing engagement (§5.3). */
    private Integer contractPeriodMonths;

    @NotBlank
    @Column(nullable = false)
    private String position;

    @Builder.Default
    @Column(nullable = false)
    private boolean probationApplicable = false;

    /** Required iff probationApplicable. 1-6. */
    private Integer probationPeriodMonths;

    /** Required iff probationApplicable. */
    @Column(precision = 10, scale = 2)
    private BigDecimal probationSalary;

    /** The standing monthly salary — post-probation figure if probation applies, otherwise the
     *  only salary figure. Always shown in the contract (§5.3, §11). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlySalary;

    /** Whole-number percentage as entered (e.g. 10.00 = 10%) — NOT the [0,1] fraction
     *  Therapist.commissionRate stores; converted on sync (§5.4). Null = no commission clause. */
    @Column(precision = 5, scale = 2)
    private BigDecimal commissionPercent;

    /** Minimum sessions/month to earn the bonus. Null = no bonus clause. Required together with
     *  performanceBonusAmount (§5.3). */
    private Integer performanceBonusThreshold;

    /** Bonus amount paid in a month where the threshold is met. Null = no bonus clause. */
    @Column(precision = 10, scale = 2)
    private BigDecimal performanceBonusAmount;

    @Column(nullable = false)
    private Integer noticePeriodMonths;

    // ---- Generated / editable content ----

    /** The free-form, owner-editable contract body (rich text HTML). Populated on draft generation
     *  from ContractTemplateRenderer, then freely edited (§5.2). Locked once APPROVED. */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contractBodyHtml;

    /** Rendered only at approve-time (§5.5); null while DRAFT. */
    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] pdfContent;

    // ---- Lifecycle metadata ----

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private User approvedBy;

    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledBy;

    @Column(length = 500)
    private String cancellationReason;

    /** In-app acknowledgment by the therapist (§5.7) — never on the PDF itself. */
    private LocalDateTime acknowledgedAt;

    /** Set when this contract was generated via "Renew" from an earlier one (§5.6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_contract_id")
    private EmploymentContract previousContract;

    @Version
    private Long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
