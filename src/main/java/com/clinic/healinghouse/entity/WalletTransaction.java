package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit ledger entry for a PatientWallet balance change. TOP_UP/REFUND carry a
 * paymentMethod since real money physically moves at that point (cash reconciliation);
 * USAGE/REVERSAL carry an appointment link instead, since they're internal transfers
 * between "wallet balance" and "amount owed on that appointment" — no money moves.
 */
@Entity
@Table(name = "wallet_transaction", indexes = {
        @Index(name = "idx_wallet_txn_patient",     columnList = "patient_id"),
        @Index(name = "idx_wallet_txn_appointment",  columnList = "appointment_id"),
        @Index(name = "idx_wallet_txn_created_at",   columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletTransactionType type;

    /** Always positive; type determines the direction of the balance change. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
