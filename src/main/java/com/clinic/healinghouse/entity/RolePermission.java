package com.clinic.healinghouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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

    // @JdbcTypeCode(SqlTypes.VARCHAR) forces a plain VARCHAR column instead of Hibernate's default
    // (for MySQL) of generating a native SQL ENUM(...) literal list at schema-creation time — without
    // it, `ddl-auto: update` never widens that native ENUM's allowed values when a new AppRole/Module/
    // PermissionAction constant is added later, and every insert of the new value fails with
    // "Data truncated for column" (hit for real when THERAPIST_PLUS/EXPENSE_CATEGORIES/EXPENSES/
    // REPORTS_PROFIT_LOSS were added — see requirements/Expenses_Requirements_v1.md).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private AppRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private Module module;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private PermissionAction action;

    @Column(nullable = false)
    private boolean granted;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
