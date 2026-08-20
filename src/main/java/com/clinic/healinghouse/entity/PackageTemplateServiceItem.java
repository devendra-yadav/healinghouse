package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "package_template_service_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "packageTemplate")
@ToString(exclude = "packageTemplate")
public class PackageTemplateServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_template_id", nullable = false)
    private PackageTemplate packageTemplate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    /** e.g. 10, for "10x Back Massage". */
    @Min(1)
    @Column(nullable = false)
    private int sessionCount;

    /** Staff-set per-item discount off this service's catalog price; null = use catalog price as-is. */
    @Column(precision = 10, scale = 2)
    private BigDecimal priceOverride;
}
