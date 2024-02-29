package com.clinic.healinghouse.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.clinic.healinghouse.dto.PatientDTO;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@ToString
public class Patient {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private int id;
	private String name;
	
	@DateTimeFormat(pattern = "yyyy-MM-dd")
	private LocalDate dateOfBirth;
	private String mobile;
	private String email;
	private String howDidYouFindUs;
	
	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm")
	private LocalDateTime firstVisitDate;
	private String addressLine1;
	private String addressLine2;
	private String city;
	private String state;
	private String country;
	private String remarks;
	
	public Patient(PatientDTO patientDto) {
		this.name = patientDto.getName();
		this.dateOfBirth = patientDto.getDateOfBirth();
		this.mobile = patientDto.getMobile();
		this.email = patientDto.getEmail();
		this.howDidYouFindUs = patientDto.getHowDidYouFindUs();
		this.firstVisitDate = patientDto.getFirstVisitDate();
		this.addressLine1 = patientDto.getAddressLine1();
		this.addressLine2 = patientDto.getAddressLine2();
		this.city = patientDto.getCity();
		this.state = patientDto.getState();
		this.country = patientDto.getCountry();
		this.remarks = patientDto.getRemarks();
	}

}
