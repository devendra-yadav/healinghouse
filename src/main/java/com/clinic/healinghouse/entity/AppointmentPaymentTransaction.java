package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dated ledger of the fresh cash/UPI/card/bank/other portion of Appointment.amountPaid — the part
 * not already sourced from a wallet top-up or package purchase (those move through WalletTransaction/
 * PackageTransaction instead). Mirrors those two ledgers' TOP_UP/REFUND vs USAGE/REVERSAL split:
 * this table only ever records real money changing hands, never the wallet/package-funded portion,
 * so summing it alongside WalletTransaction.TOP_UP/REFUND and PackageTransaction.PURCHASE/REFUND for
 * a cash-flow report can never double-count.
 * <p>
 * RECEIVED is a new payment (create, or "New Payment" on edit) — amount is always positive.
 * CORRECTED is a write via the "Amount Prepaid" pencil-edit, which can move the total up or down
 * (e.g. fixing a data-entry mistake, or recording that cash was physically handed back to a patient
 * for a cancelled appointment) — amount is signed accordingly. Written by AppointmentService only
 * when the cash portion (amountPaid - walletAmountApplied - packageAmountApplied) actually changes;
 * wallet/package reversal never touches this table since it moves amountPaid and its own applied
 * field by the same amount, leaving the cash portion unchanged.
 * <p>
 * A downward CORRECTED amount is inherently ambiguous — "fixing a data-entry mistake" and "cash
 * physically handed back" are both legitimate reasons the recorded total might drop, but only the
 * second one is a real cash-flow event. {@link #cashPhysicallyReturned} disambiguates the two: staff
 * explicitly flags it via the pencil-edit UI when money actually left the till, defaulting to false
 * (assume a data-entry fix) otherwise. Only consulted for a negative {@code amount} — a positive
 * RECEIVED/CORRECTED amount is unambiguously real money in either way. CashFlowReportAggregator
 * excludes a negative CORRECTED row from every total/ledger entry unless this is true, so a typo fix
 * no longer inflates Total Outflow the way an unconditional count previously did.
 */
@Entity
@Table(name = "appointment_payment_transaction", indexes = {
        @Index(name = "idx_appt_pay_txn_appointment", columnList = "appointment_id"),
        @Index(name = "idx_appt_pay_txn_patient",     columnList = "patient_id"),
        @Index(name = "idx_appt_pay_txn_created_at",  columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentPaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentPaymentTransactionType type;

    /** Signed: positive for money received, negative for a downward correction. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    @Column(length = 255)
    private String note;

    /** Only meaningful when {@code amount} is negative — see the class javadoc. Irrelevant (and left
     *  false) for RECEIVED rows and upward CORRECTED rows, which are always real money in. Explicit
     *  DEFAULT 0 (mirrors {@code Therapist.owner}) so {@code ddl-auto: update} can add this NOT NULL
     *  column to an already-populated table without failing. */
    @Builder.Default
    @Column(nullable = false, columnDefinition = "TINYINT(1) NOT NULL DEFAULT 0")
    private boolean cashPhysicallyReturned = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
