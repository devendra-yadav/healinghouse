package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Treatment / therapy catalog entry.
 * Named ClinicService to avoid collision with Spring's @Service annotation.
 */
@Entity
@Table(name = "service", indexes = {
        @Index(name = "idx_service_category", columnList = "category"),
        @Index(name = "idx_service_active",   columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** E.g. "Massage", "Acupuncture", "TCM", "Detox", "IonTherapy", "Compression", "Hijama", "Other" */
    private String category;

    private Integer durationMinutes;

    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}