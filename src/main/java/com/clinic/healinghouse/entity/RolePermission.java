package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One (role, module, action) grant/deny cell of the Access Control Matrix
 * (requirements/Security_RBAC_Requirements_v1.md §4, §6.3). Seeded on first boot from the matrix
 * defaults, read into {@code PermissionService}'s in-memory cache, and — from Phase D onward —
 * editable via the Access Matrix UI. Not every (module, action) combination is meaningful (e.g.
 * APPROVE on TAGS); only rows with an actual enforcement point in the app are seeded.
 */
@Entity
@Table(name = "role_permission",
        uniqueConstraints = @UniqueConstraint(columnNames = {"role", "module", "action"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Module module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionAction action;

    @Column(nullable = false)
    private boolean granted;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
