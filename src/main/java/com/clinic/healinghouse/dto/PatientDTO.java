package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter 
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class PatientDTO {
	
	@NotNull(message = "Patient name cant be blank")
	@NotBlank(message = "Patient name cant be blank")
	private String name;
	private LocalDate dateOfBirth;
	private String mobile;
	private String email;
	private String howDidYouFindUs;
	private LocalDateTime firstVisitDate = LocalDateTime.now();
	private String addressLine1;
	private String addressLine2;
	private String city;
	private String state;
	private String country;
	private String remarks;
}
