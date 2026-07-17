package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A login account, distinct from {@link Therapist} (a clinical staff/service-provider master-data
 * row). A THERAPIST-role user must be linked to an existing Therapist row via {@code therapist} —
 * that link is what "own appointments/earnings" scoping resolves against everywhere else in the
 * app (requirements/Security_RBAC_Requirements_v1.md §6.1, §7). Other roles leave it null.
 */
@Entity
@Table(name = "app_user", indexes = {
        @Index(name = "idx_app_user_username", columnList = "username", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank
    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "therapist_id")
    private Therapist therapist;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean mustChangePassword = false;

    private LocalDateTime lastLoginAt;

    @Builder.Default
    @Column(nullable = false)
    private int failedLoginAttempts = 0;

    private LocalDateTime lockedUntil;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}