package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Per-appointment record of "combo X was applied here, with this discount" — decoupled
 * from the live Combo catalog entry the same way AppointmentServiceLine.priceAtTime is
 * decoupled from ClinicService.price. If the combo is later edited or deactivated, past
 * appointments' AppointmentCombo rows and savings figures are unaffected.
 */
@Entity
@Table(name = "appointment_combo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "appointment")
@ToString(exclude = "appointment")
public class AppointmentCombo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "combo_id", nullable = false)
    private Combo combo;

    /** Combo name at the moment it was added — survives later renames of the Combo catalog entry. */
    @Column(nullable = false)
    private String comboNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DiscountType discountType;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Resolved, capped rupee discount applied to this combo's own lines only. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    /** Sum of this combo's lines' raw lineTotal at the moment it was added — informational, for the savings badge. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalSubtotalSnapshot;
}
