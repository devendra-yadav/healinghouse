package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "combo_service_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "combo")
@ToString(exclude = "combo")
public class ComboServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "combo_id", nullable = false)
    private Combo combo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    @Min(1)
    @Builder.Default
    @Column(nullable = false)
    private int quantity = 1;

    /** Staff-set per-item discount off this service's catalog price; null = use catalog price as-is. */
    @Column(precision = 10, scale = 2)
    private BigDecimal priceOverride;
}
