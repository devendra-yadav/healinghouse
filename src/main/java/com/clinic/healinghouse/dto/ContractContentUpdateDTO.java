package com.clinic.healinghouse.dto;

import lombok.Data;

/** The free-form review/edit save (requirements/Employment_Contracts_Requirements_v1.md §4.2). */
@Data
public class ContractContentUpdateDTO {
    private Long id;
    private String contractBodyHtml;
}
