package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "appointment", indexes = {
        @Index(name = "idx_appt_datetime",  columnList = "appointment_date_time"),
        @Index(name = "idx_appt_status",    columnList = "status"),
        @Index(name = "idx_appt_patient",   columnList = "patient_id"),
        @Index(name = "idx_appt_therapist", columnList = "therapist_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"serviceLines", "productLines", "combos"})
@ToString(exclude = {"serviceLines", "productLines", "combos"})
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "therapist_id", nullable = false)
    private Therapist therapist;

    @Column(nullable = false)
    private LocalDateTime appointmentDateTime;

    /** How long the therapist is occupied for, in minutes. Drives conflict detection and the calendar view. */
    @Column(nullable = false, columnDefinition = "INT NOT NULL DEFAULT 60")
    @Builder.Default
    private Integer durationMinutes = 60;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.SCHEDULED;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(length = 500)
    private String cancelReason;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal totalServiceAmount = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal totalProductAmount = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private DiscountType discountType = DiscountType.NONE;

    /** Raw value staff typed: a 0-100 percentage or a flat rupee amount, per discountType. */
    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Resolved, capped rupee amount actually applied — drives grandTotal and per-line distribution. */
    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;

    /** Current wallet-sourced portion of amountPaid — mirrors discountAmount as a resolved, persisted value. */
    @Column(name = "wallet_amount_applied", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal walletAmountApplied = BigDecimal.ZERO;

    /** Sum of priceAtTime across every line covered by a prepaid package session — mirrors walletAmountApplied. */
    @Column(name = "package_amount_applied", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal packageAmountApplied = BigDecimal.ZERO;

    @OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AppointmentServiceLine> serviceLines = new ArrayList<>();

    @OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AppointmentProductLine> productLines = new ArrayList<>();

    @OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AppointmentCombo> combos = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Guards against a lost-update race on status transitions / wallet application (e.g. a
     * double-clicked "Cancel" or a double-submitted edit): a second write against a stale copy
     * of this row now fails fast with ObjectOptimisticLockingFailureException instead of silently
     * re-applying its own wallet reversal/debit on top of the first, already-committed one. See
     * AppointmentService.saveWithConflictCheck.
     */
    @Version
    private Long version;

    /** Therapists on any service/product line that differ from the main therapist. */
    @Transient
    public List<Therapist> getOtherLineTherapists() {
        Map<Long, Therapist> others = new LinkedHashMap<>();
        serviceLines.forEach(sl -> {
            if (!sl.getTherapist().getId().equals(therapist.getId())) others.put(sl.getTherapist().getId(), sl.getTherapist());
        });
        productLines.forEach(pl -> {
            if (!pl.getTherapist().getId().equals(therapist.getId())) others.put(pl.getTherapist().getId(), pl.getTherapist());
        });
        return new ArrayList<>(others.values());
    }

    @Transient
    public LocalDateTime getEndDateTime() {
        int minutes = durationMinutes != null ? durationMinutes : 60;
        return appointmentDateTime.plusMinutes(minutes);
    }

    @Transient
    public BigDecimal getBalanceDue() {
        BigDecimal total = grandTotal != null ? grandTotal : BigDecimal.ZERO;
        BigDecimal paid  = amountPaid != null ? amountPaid : BigDecimal.ZERO;
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    @Transient
    public boolean isDiscounted() {
        return discountAmount != null && discountAmount.signum() > 0;
    }

    /** Sum of every combo's own resolved discount on this appointment — separate from the whole-appointment discountAmount. */
    @Transient
    public BigDecimal getTotalComboDiscount() {
        return combos.stream()
                .map(AppointmentCombo::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Template-friendly line filters — kept here rather than as inline SpEL selection expressions
    // (Thymeleaf's `.?[...]` operator evaluates its predicate with the candidate element as root,
    // so an outer th:each variable like a combo isn't visible inside it; see detail.html).
    @Transient
    public List<AppointmentServiceLine> getStandaloneServiceLines() {
        return serviceLines.stream().filter(sl -> sl.getAppointmentCombo() == null).toList();
    }

    @Transient
    public List<AppointmentProductLine> getStandaloneProductLines() {
        return productLines.stream().filter(pl -> pl.getAppointmentCombo() == null).toList();
    }

    /** Raw (undiscounted) total of standalone service lines only — what the Services table's footer should show, not totalServiceAmount (which also includes combo lines shown separately). */
    @Transient
    public BigDecimal getStandaloneServiceTotal() {
        return getStandaloneServiceLines().stream()
                .map(AppointmentServiceLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Raw (undiscounted) total of standalone product lines only — mirrors getStandaloneServiceTotal. */
    @Transient
    public BigDecimal getStandaloneProductTotal() {
        return getStandaloneProductLines().stream()
                .map(AppointmentProductLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transient
    public List<AppointmentServiceLine> getServiceLinesForCombo(AppointmentCombo ac) {
        return serviceLines.stream().filter(sl -> sl.getAppointmentCombo() == ac).toList();
    }

    @Transient
    public List<AppointmentProductLine> getProductLinesForCombo(AppointmentCombo ac) {
        return productLines.stream().filter(pl -> pl.getAppointmentCombo() == ac).toList();
    }

    /**
     * "PAID" | "PARTIAL" | "UNPAID" | "N/A" — used in list/detail views. A ₹0 grandTotal (e.g. a
     * 100%-discounted appointment) means nothing is owed, not that pricing is missing — it reads
     * PAID like any other appointment with balanceDue == 0, rather than the indistinguishable-from-
     * unpriced "N/A" a straight zero-check would give it. Only a genuinely absent grandTotal is N/A.
     */
    @Transient
    public String getPaymentStatus() {
        if (grandTotal == null) return "N/A";
        if (grandTotal.signum() == 0) return "PAID";
        if (amountPaid == null || amountPaid.signum() == 0) return "UNPAID";
        if (amountPaid.compareTo(grandTotal) >= 0) return "PAID";
        return "PARTIAL";
    }
}