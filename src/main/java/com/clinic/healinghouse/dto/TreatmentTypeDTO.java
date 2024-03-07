package com.clinic.healinghouse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString
@AllArgsConstructor @NoArgsConstructor
public class TreatmentTypeDTO {
	
	@NotBlank(message = "Name cant be blank")
	private String name;
	private Integer price;
	private String comments;
	
}
