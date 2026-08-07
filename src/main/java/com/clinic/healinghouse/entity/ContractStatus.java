package com.clinic.healinghouse.entity;

/** Lifecycle of an {@link EmploymentContract} (requirements/Employment_Contracts_Requirements_v1.md §3.1). */
public enum ContractStatus {
    DRAFT,       // freely editable, no PDF yet, not the therapist's official contract
    APPROVED,    // locked content, PDF rendered, the therapist's one active contract
    CANCELLED,   // was APPROVED, terminated early by the Owner — terminal
    SUPERSEDED   // was APPROVED, replaced by a renewal — terminal
}
