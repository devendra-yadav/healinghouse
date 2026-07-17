package com.clinic.healinghouse.entity;

/** Access-control unit the RBAC matrix (requirements/Security_RBAC_Requirements_v1.md §4, §6.2) grants
 *  permissions against — one entry per row of that table. Kept as a separate module per catalog type
 *  (SERVICES/PRODUCTS/COMBOS/PACKAGE_TEMPLATES) even though they share identical seeded defaults, so
 *  the Access Matrix UI can diverge them later without a schema change. */
public enum Module {
    DASHBOARD, PATIENTS, APPOINTMENTS, THERAPISTS,
    SERVICES, PRODUCTS, COMBOS, PACKAGE_TEMPLATES, PATIENT_PACKAGES,
    TAGS, WALLET,
    REPORTS_STANDARD, REPORTS_REVENUE,
    USER_MANAGEMENT, ACCESS_MATRIX
}
