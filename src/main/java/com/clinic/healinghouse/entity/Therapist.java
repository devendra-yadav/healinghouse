package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "therapist", indexes = {
        @Index(name = "idx_therapist_phone", columnList = "phone")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Therapist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String fullName;

    private String specialization;

    private String phone;

    private String email;

    /** Typically 0 or null for the owner — no salary calculation applies to them (see {@code owner}). */
    @DecimalMin(value = "0", message = "Fixed monthly salary cannot be negative.")
    @Column(precision = 10, scale = 2)
    private BigDecimal fixedMonthlySalary;

    /** Stored as decimal fraction, e.g. 0.1000 = 10%. Typically null/0 for the owner. Bounded to
     *  [0,1] — CommissionCalculator multiplies this straight against revenue, so an unbounded field
     *  lets a typo'd "50" (meant as 50%, i.e. 0.5) become an accidental 5000% commission. */
    @DecimalMin(value = "0", message = "Commission rate cannot be negative.")
    @DecimalMax(value = "1", message = "Commission rate cannot exceed 1 (100%).")
    @Column(precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /** Minimum services count in a month to earn the bonus. */
    @Min(value = 0, message = "Performance bonus threshold cannot be negative.")
    private Integer performanceBonusThreshold;

    @DecimalMin(value = "0", message = "Performance bonus amount cannot be negative.")
    @Column(precision = 10, scale = 2)
    private BigDecimal performanceBonusAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /**
     * True when this therapist is the clinic owner (Marcia Gomes Yadav) — no commission/bonus
     * payout calculation applies to them, regardless of what fixedMonthlySalary/commissionRate hold.
     * An explicit, staff-set flag rather than inferring "owner" from those two fields being zero/null
     * (the old behavior): a brand-new therapist saved before her payout terms are configured would
     * otherwise be indistinguishable from the owner and silently earn ₹0 commission. Defaults to
     * false, so every new therapist is a normal payout-earning therapist unless explicitly marked.
     * DB default is 0/false at the column level (existing rows backfill to non-owner automatically);
     * {@code config.OwnerFlagBackfill} does a one-time, idempotent fix-up of the pre-existing owner
     * row in databases that were seeded before this column existed.
     */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "TINYINT(1) NOT NULL DEFAULT 0")
    private boolean owner = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}