package com.clinic.healinghouse.config;

import org.hibernate.dialect.MySQLDialect;

/**
 * Stops Hibernate from auto-generating a CHECK constraint enumerating the current values of every
 * {@code @Enumerated(EnumType.STRING)} column ({@code RolePermission.role/module/action},
 * {@code User.role}, {@code Expense.status}, {@code RecurringExpenseTemplate.frequency},
 * {@code AuditLog.action}) — {@code ddl-auto: update} only ever ADDS schema elements, so it never
 * widens such a constraint when the mapped enum later gains a new constant. Hit for real adding
 * {@code Module.AUDIT_LOG}: every insert of an AUDIT_LOG {@code RolePermission} row failed with
 * "Check constraint ... is violated" against a table created before that value existed.
 * {@link StaleCheckConstraintBackfill} does the matching one-time cleanup of constraints already
 * created (by the default dialect) before this one was configured.
 */
public class HealingHouseMySQLDialect extends MySQLDialect {

    @Override
    public boolean supportsColumnCheck() {
        return false;
    }
}
