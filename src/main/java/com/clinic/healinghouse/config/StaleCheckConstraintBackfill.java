package com.clinic.healinghouse.config;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time idempotent fix-up (an always-on {@code CommandLineRunner}, same style as the other
 * self-healing config/ backfill runners) for every CHECK
 * constraint Hibernate auto-generated on an enum column before {@link HealingHouseMySQLDialect}
 * disabled that behavior — see that class's javadoc for why they exist and why
 * {@code ddl-auto: update} can never self-heal them on its own. No custom {@code @Check} constraint
 * is used anywhere in this codebase, so every CHECK constraint found in the schema is one of these
 * synthetic enum guards and safe to drop unconditionally. Runs at {@code HIGHEST_PRECEDENCE} —
 * before {@code SecuritySeeder}, which would otherwise fail inserting the new AUDIT_LOG permission
 * row against the still-stale {@code role_permission} constraint on any pre-existing database.
 * A no-op on every boot after the first, and on a fresh database (the new dialect never creates one).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class StaleCheckConstraintBackfill implements CommandLineRunner {

    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        dropAllCheckConstraints();
    }

    @SuppressWarnings("unchecked")
    private void dropAllCheckConstraints() {
        List<Object[]> rows = entityManager.createNativeQuery(
                "select table_name, constraint_name from information_schema.table_constraints "
                        + "where table_schema = database() and constraint_type = 'CHECK'").getResultList();
        for (Object[] row : rows) {
            String tableName = (String) row[0];
            String constraintName = (String) row[1];
            entityManager.createNativeQuery(
                    "alter table `" + tableName + "` drop check `" + constraintName + "`").executeUpdate();
            log.info("Dropped stale check constraint '{}' on table '{}'.", constraintName, tableName);
        }
    }
}
