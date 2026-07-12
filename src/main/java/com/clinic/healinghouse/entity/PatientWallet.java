package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A patient's prepaid balance. Created lazily on first use (first top-up, or the
 * first time an appointment needs to show "available balance") — most patients
 * never use this feature, so there is no row-per-patient seeding.
 * Shares its primary key with Patient via @MapsId, so findById(patientId) on the
 * repository already means "find this patient's wallet."
 */
@Entity
@Table(name = "patient_wallet")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientWallet {

    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    private Long version;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
