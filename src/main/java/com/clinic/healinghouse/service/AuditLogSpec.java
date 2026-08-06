package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.AuditAction;
import com.clinic.healinghouse.entity.AuditLog;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Reusable JPA Specifications for dynamic AuditLog list filtering.
 * Combine with Specification.where(...).and(...) in AuditLogService.
 */
public class AuditLogSpec {

    private AuditLogSpec() {
    }

    public static Specification<AuditLog> betweenDates(LocalDateTime start, LocalDateTime end) {
        return (root, query, cb) -> {
            if (start == null && end == null) return cb.conjunction();
            if (start == null) return cb.lessThanOrEqualTo(root.get("timestamp"), end);
            if (end == null) return cb.greaterThanOrEqualTo(root.get("timestamp"), start);
            return cb.between(root.get("timestamp"), start, end);
        };
    }

    public static Specification<AuditLog> hasUsername(String username) {
        return (root, query, cb) ->
                !StringUtils.hasText(username) ? cb.conjunction()
                        : cb.equal(cb.lower(root.get("username")), username.trim().toLowerCase());
    }

    public static Specification<AuditLog> hasEntityType(String entityType) {
        return (root, query, cb) ->
                !StringUtils.hasText(entityType) ? cb.conjunction()
                        : cb.equal(root.get("entityType"), entityType);
    }

    public static Specification<AuditLog> hasAction(AuditAction action) {
        return (root, query, cb) ->
                action == null ? cb.conjunction() : cb.equal(root.get("action"), action);
    }
}
