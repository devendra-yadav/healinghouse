package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "appointment_product", indexes = {
        @Index(name = "idx_product_line_therapist", columnList = "therapist_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "appointment")
@ToString(exclude = "appointment")
public class AppointmentProductLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Therapist who actually sold/administered this product — defaults to the appointment's main therapist. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "therapist_id", nullable = false)
    private Therapist therapist;

    @Min(1)
    @Column(nullable = false)
    private int quantity;

    /** Price snapshot at the time of the appointment. */
    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtTime;

    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    /** Post-discount line total; null when the appointment has no discount applied. */
    @Column(precision = 10, scale = 2)
    private BigDecimal discountedLineTotal;

    /** Discounted total if a discount has been distributed to this line, else the raw line total. */
    @Transient
    public BigDecimal getEffectiveLineTotal() {
        return discountedLineTotal != null ? discountedLineTotal : lineTotal;
    }
}