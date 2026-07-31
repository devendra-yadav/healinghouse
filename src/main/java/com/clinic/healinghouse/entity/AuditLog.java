package com.clinic.healinghouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Immutable CRUD change-history row — who changed which field on which entity, and when. Written
 * automatically by {@code config.AuditLogEventListener} off Hibernate's own insert/update/delete
 * flush events, never by application code directly. Excludes its own type (no self-logging) and
 * the append-only {@code WalletTransaction}/{@code PackageTransaction} ledgers, which are already
 * their own audit trail for money/session movements.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_log_entity", columnList = "entityType,entityId"),
        @Index(name = "idx_audit_log_username", columnList = "username")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime timestamp;

    /** Nullable — background/system-triggered changes (e.g. the recurring-expense scheduler) have no logged-in user. */
    private Long userId;

    @Column(nullable = false)
    private String username;

    // @JdbcTypeCode(SqlTypes.VARCHAR) — see RolePermission's identical annotation: forces a plain
    // VARCHAR column instead of a native MySQL ENUM(...), so adding a new AuditAction constant
    // later never hits a "Data truncated for column" insert failure under ddl-auto: update.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private String entityId;

    @Column(length = 4000)
    private String details;

    private String ipAddress;
}
