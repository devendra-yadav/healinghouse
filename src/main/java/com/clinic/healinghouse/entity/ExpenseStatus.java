package com.clinic.healinghouse.entity;

/** requirements/Expenses_Requirements_v1.md §3.2, §5.2 — soft-void lifecycle, never a hard delete. */
public enum ExpenseStatus {
    ACTIVE,   // counts toward every expense list/report
    VOIDED    // soft-deleted; excluded from reports by default, immutable
}
