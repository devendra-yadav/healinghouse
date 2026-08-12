package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Staff-managed, optional catalog of reusable package bundles. The suggested price is always
 * computed transiently from current catalog prices — never stored — mirroring Combo; it's only
 * ever a starting point for a sale (see PackageService.computeSuggestedPrice), never binding on
 * the PatientPackage actually sold.
 */
@Entity
@Table(name = "package_template", indexes = {
        @Index(name = "idx_package_template_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"serviceItems", "productItems"})
@ToString(exclude = {"serviceItems", "productItems"})
public class PackageTemplate {

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

    @OneToMany(mappedBy = "packageTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PackageTemplateServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "packageTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PackageTemplateProductItem> productItems = new ArrayList<>();

    /** Guards the "a template can never have zero items while active" invariant against concurrent
     *  service+product deactivation — mirrors Combo's identical @Version (Bug_Report_v6.md
     *  Finding 10) and closes the same gap for PackageTemplate, which had been left without it even
     *  though PackageTemplateService.handleServiceDeactivated/handleProductDeactivated (added for
     *  Bug_Report_v6.md Finding 9) use the exact same strip-items-then-auto-deactivate-if-empty
     *  pattern (Bug_Report_v7.md Finding 10). Like Combo's version, the mutation that matters here
     *  (removing an item from serviceItems/productItems) lives on the inverse side of a mappedBy
     *  collection, so a plain save() wouldn't dirty this row or bump this column;
     *  PackageTemplateService.removeFromTemplates force-increments it explicitly. */
    @Version
    private Long version;
}
