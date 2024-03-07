package com.clinic.healinghouse.dto;

import java.util.HashMap;
import java.util.List;

import com.clinic.healinghouse.entity.TreatmentType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString
@AllArgsConstructor @NoArgsConstructor
public class PackageDTO {
	
	private String packageName;
	private HashMap<TreatmentType, Integer> allTreatmentsMap;

}
