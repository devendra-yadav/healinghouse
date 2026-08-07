package com.clinic.healinghouse.dto;

import lombok.Data;

/** requirements/Employment_Contracts_Requirements_v1.md §4.4. */
@Data
public class ContractCancelForm {
    private Long id;
    private String cancellationReason;
}
