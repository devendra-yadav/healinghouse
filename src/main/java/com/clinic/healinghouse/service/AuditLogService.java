package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.AuditAction;
import com.clinic.healinghouse.entity.AuditLog;
import com.clinic.healinghouse.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Read-only query layer behind {@code /admin/audit-log} — the log itself is written exclusively
 *  by {@code config.AuditLogEventListener}, never through this service. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public Page<AuditLog> search(LocalDateTime start, LocalDateTime end, String username,
                                  String entityType, AuditAction action, Pageable pageable) {
        Specification<AuditLog> spec = Specification
                .where(AuditLogSpec.betweenDates(start, end))
                .and(AuditLogSpec.hasUsername(username))
                .and(AuditLogSpec.hasEntityType(entityType))
                .and(AuditLogSpec.hasAction(action));
        return auditLogRepository.findAll(spec, pageable);
    }

    /** Distinct entity-type values logged so far, to populate the filter dropdown. */
    public List<String> distinctEntityTypes() {
        return auditLogRepository.findDistinctEntityTypes();
    }
}
