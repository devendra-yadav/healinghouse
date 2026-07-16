package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit ledger entry for a PatientPackage change — mirrors WalletTransaction. PURCHASE/REFUND
 * carry a paymentMethod since real money physically moves (cash reconciliation); USAGE/REVERSAL
 * carry the specific item drawn from plus an appointment link instead, since they're internal
 * session transfers — no money moves.
 */
@Entity
@Table(name = "package_transaction", indexes = {
        @Index(name = "idx_package_txn_patient_package", columnList = "patient_package_id"),
        @Index(name = "idx_package_txn_appointment",      columnList = "appointment_id"),
        @Index(name = "idx_package_txn_created_at",       columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_package_id", nullable = false)
    private PatientPackage patientPackage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PackageTransactionType type;

    /** Always positive; type determines direction/meaning. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Set for PURCHASE/REFUND only. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    /** Set for USAGE/REVERSAL only — exactly one of these two is non-null, matching whichever item was drawn from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_package_service_item_id")
    private PatientPackageServiceItem patientPackageServiceItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_package_product_item_id")
    private PatientPackageProductItem patientPackageProductItem;

    /** Set for USAGE/REVERSAL only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
