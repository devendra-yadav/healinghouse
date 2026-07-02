package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "appointment_product")
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
}