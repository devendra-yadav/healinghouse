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
@EqualsAndHashCode(exclude = {"serviceLines", "productLines"})
@ToString(exclude = {"serviceLines", "productLines"})
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

    @OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AppointmentServiceLine> serviceLines = new ArrayList<>();

    @OneToMany(mappedBy = "appointment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AppointmentProductLine> productLines = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

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
    public BigDecimal getBalanceDue() {
        BigDecimal total = grandTotal != null ? grandTotal : BigDecimal.ZERO;
        BigDecimal paid  = amountPaid != null ? amountPaid : BigDecimal.ZERO;
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    @Transient
    public boolean isDiscounted() {
        return discountAmount != null && discountAmount.signum() > 0;
    }

    /** "PAID" | "PARTIAL" | "UNPAID" | "N/A" — used in list/detail views. */
    @Transient
    public String getPaymentStatus() {
        if (grandTotal == null || grandTotal.signum() == 0) return "N/A";
        if (amountPaid == null || amountPaid.signum() == 0) return "UNPAID";
        if (amountPaid.compareTo(grandTotal) >= 0) return "PAID";
        return "PARTIAL";
    }
}