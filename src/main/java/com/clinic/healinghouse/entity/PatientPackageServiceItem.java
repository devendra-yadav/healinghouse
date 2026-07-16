package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "patient_package_service_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "patientPackage")
@ToString(exclude = "patientPackage")
public class PatientPackageServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_package_id", nullable = false)
    private PatientPackage patientPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    @Column(nullable = false)
    private int sessionsTotal;

    @Builder.Default
    @Column(nullable = false)
    private int sessionsUsed = 0;

    /**
     * This item's proportional share of the parent PatientPackage's totalPrice, resolved at sale
     * time via ProportionalAllocator. Drives refund math (Σ priceAllocated x sessionsRemaining/
     * sessionsTotal); not used for revenue (a package sale isn't revenue — see business rules).
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAllocated;

    @Transient
    public int getSessionsRemaining() {
        return sessionsTotal - sessionsUsed;
    }
}
