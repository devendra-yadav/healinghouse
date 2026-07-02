package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "product", indexes = {
        @Index(name = "idx_product_category", columnList = "category"),
        @Index(name = "idx_product_active",   columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** E.g. "Herbal Supplement", "Oil", "Tea", "Detox Kit", "Capsule", "Other" */
    private String category;

    @NotNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Min(0)
    @Builder.Default
    @Column(nullable = false)
    private int stockQuantity = 0;

    @Builder.Default
    @Column(nullable = false)
    private int reorderLevel = 5;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Transient
    public boolean isLowStock() {
        return stockQuantity <= reorderLevel;
    }
}