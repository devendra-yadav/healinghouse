package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "appointment_service", indexes = {
        @Index(name = "idx_service_line_therapist", columnList = "therapist_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "appointment")
@ToString(exclude = "appointment")
public class AppointmentServiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    /** Therapist who actually performed this service — defaults to the appointment's main therapist. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "therapist_id", nullable = false)
    private Therapist therapist;

    /** Price snapshot at the time of the appointment. */
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtTime;

    @Min(1)
    @Builder.Default
    @Column(nullable = false)
    private int quantity = 1;

    /** Post-discount line total; null when the appointment has no discount applied. */
    @Column(precision = 10, scale = 2)
    private BigDecimal discountedLineTotal;

    @Transient
    public BigDecimal getLineTotal() {
        return priceAtTime.multiply(BigDecimal.valueOf(quantity));
    }

    /** Discounted total if a discount has been distributed to this line, else the raw line total. */
    @Transient
    public BigDecimal getEffectiveLineTotal() {
        return discountedLineTotal != null ? discountedLineTotal : getLineTotal();
    }
}