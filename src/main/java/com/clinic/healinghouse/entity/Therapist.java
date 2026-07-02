package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
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

    /** 0 or null for the owner (Marcia Gomes Yadav) — no salary calculation. */
    @Column(precision = 10, scale = 2)
    private BigDecimal fixedMonthlySalary;

    /** Stored as decimal fraction, e.g. 0.1000 = 10%. Null/0 for owner. */
    @Column(precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /** Minimum services count in a month to earn the bonus. */
    private Integer performanceBonusThreshold;

    @Column(precision = 10, scale = 2)
    private BigDecimal performanceBonusAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** True when this therapist is the owner — no payout calculation applies. */
    @Transient
    public boolean isOwner() {
        return (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) == 0)
                && (fixedMonthlySalary == null || fixedMonthlySalary.compareTo(BigDecimal.ZERO) == 0);
    }
}