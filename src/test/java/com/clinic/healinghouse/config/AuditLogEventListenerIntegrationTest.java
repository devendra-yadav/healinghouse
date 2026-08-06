package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.AuditAction;
import com.clinic.healinghouse.entity.AuditLog;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.repository.AuditLogRepository;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link AuditLogEventListener} end-to-end against a real Hibernate flush (not mocks) —
 * each repository call below runs in its own naturally-committing transaction (Spring Data's
 * default per-method @Transactional), which is what actually triggers the listener's beforeCommit
 * flush, unlike a test wrapped in its own rolled-back @Transactional would.
 */
@SpringBootTest
class AuditLogEventListenerIntegrationTest {

    @Autowired
    private ExpenseCategoryRepository expenseCategoryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void recordsCreateUpdateAndDeleteForAPlainEntity() {
        ExpenseCategory saved = expenseCategoryRepository.save(
                ExpenseCategory.builder().name("AuditTestCategory").build());
        Long id = saved.getId();

        saved.setName("AuditTestCategoryRenamed");
        expenseCategoryRepository.save(saved);

        expenseCategoryRepository.delete(saved);

        try {
            List<AuditLog> entries = auditLogRepository.findAll().stream()
                    .filter(e -> "ExpenseCategory".equals(e.getEntityType()) && String.valueOf(id).equals(e.getEntityId()))
                    .sorted((a, b) -> a.getId().compareTo(b.getId()))
                    .toList();

            assertThat(entries).hasSize(3);

            AuditLog create = entries.get(0);
            assertThat(create.getAction()).isEqualTo(AuditAction.CREATE);
            assertThat(create.getDetails()).contains("name=AuditTestCategory");

            AuditLog update = entries.get(1);
            assertThat(update.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(update.getDetails()).contains("name: AuditTestCategory -> AuditTestCategoryRenamed");
            assertThat(update.getDetails()).doesNotContain("updatedAt").doesNotContain("version");

            AuditLog delete = entries.get(2);
            assertThat(delete.getAction()).isEqualTo(AuditAction.DELETE);

            auditLogRepository.deleteAll(entries);
        } finally {
            expenseCategoryRepository.findById(id).ifPresent(expenseCategoryRepository::delete);
        }
    }
}
