package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An actual sold package instance for one patient — a bundle of specific services/products,
 * prepaid once, drawn down one session at a time across future appointments. Decoupled from
 * PackageTemplate the same way AppointmentCombo is decoupled from Combo: sourceTemplate is an
 * informational backlink only, never re-read after sale, so a later template edit/deactivation
 * can't affect this row.
 */
@Entity
@Table(name = "patient_package", indexes = {
        @Index(name = "idx_patient_package_patient", columnList = "patient_id"),
        @Index(name = "idx_patient_package_status",  columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"serviceItems", "productItems"})
@ToString(exclude = {"serviceItems", "productItems"})
public class PatientPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** Set only if sold from a template; null for a fully custom package. Informational only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_template_id")
    private PackageTemplate sourceTemplate;

    @Column(nullable = false)
    private String name;

    /** What the patient actually paid, in full, at sale. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /** Null = never expires. */
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatientPackageStatus status;

    /** Guards concurrent consumption racing against cancellation/refund. */
    @Version
    private Long version;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime purchasedAt;

    @OneToMany(mappedBy = "patientPackage", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PatientPackageServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "patientPackage", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PatientPackageProductItem> productItems = new ArrayList<>();
}
