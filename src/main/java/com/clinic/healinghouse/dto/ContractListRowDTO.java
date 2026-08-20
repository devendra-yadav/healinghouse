package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.ContractStatus;
import com.clinic.healinghouse.entity.EmploymentContract;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** requirements/Employment_Contracts_Requirements_v1.md §4.3 — read-only projection for the
 *  Therapist detail page's current-contract summary and contract-history list. */
public record ContractListRowDTO(
        Long id,
        String contractNumber,
        ContractStatus status,
        String position,
        LocalDate joiningDate,
        LocalDateTime approvedAt,
        LocalDateTime acknowledgedAt
) {
    public static ContractListRowDTO from(EmploymentContract contract) {
        return new ContractListRowDTO(
                contract.getId(),
                contract.getContractNumber(),
                contract.getStatus(),
                contract.getPosition(),
                contract.getJoiningDate(),
                contract.getApprovedAt(),
                contract.getAcknowledgedAt()
        );
    }
}
