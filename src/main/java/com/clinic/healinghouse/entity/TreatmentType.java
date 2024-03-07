package com.clinic.healinghouse.entity;

import com.clinic.healinghouse.dto.TreatmentTypeDTO;

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
@Getter @Setter @ToString
@NoArgsConstructor @AllArgsConstructor
public class TreatmentType {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private int id;
	private String name;
	private Integer price;
	private String comments;
	
	public TreatmentType(TreatmentTypeDTO treatmentTypeDto) {
		this.name = treatmentTypeDto.getName();
		this.price = treatmentTypeDto.getPrice();
		if(price==null) {
			price = 0;
		}
		this.comments = treatmentTypeDto.getComments();
	}

}
