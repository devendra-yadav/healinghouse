package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "patient_package_product_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "patientPackage")
@ToString(exclude = "patientPackage")
public class PatientPackageProductItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_package_id", nullable = false)
    private PatientPackage patientPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int sessionsTotal;

    @Builder.Default
    @Column(nullable = false)
    private int sessionsUsed = 0;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAllocated;

    @Transient
    public int getSessionsRemaining() {
        return sessionsTotal - sessionsUsed;
    }
}
