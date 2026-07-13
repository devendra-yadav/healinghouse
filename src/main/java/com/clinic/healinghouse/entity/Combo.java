package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin-managed bundle of services/products sold as a single deal.
 * Original price and combo (selling) price are always computed transiently from
 * current catalog prices — never stored — so a combo never goes stale relative
 * to catalog price changes (see ComboService.computeOriginalPrice/computeComboPrice).
 */
@Entity
@Table(name = "combo", indexes = {
        @Index(name = "idx_combo_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"serviceItems", "productItems"})
@ToString(exclude = {"serviceItems", "productItems"})
public class Combo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private DiscountType discountType = DiscountType.NONE;

    /** Raw value staff typed: a 0-100 percentage or a flat rupee amount, per discountType. */
    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;

    @OneToMany(mappedBy = "combo", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ComboServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "combo", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ComboProductItem> productItems = new ArrayList<>();
}
