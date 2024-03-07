package com.clinic.healinghouse.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.clinic.healinghouse.dto.TreatmentTypeDTO;
import com.clinic.healinghouse.entity.TreatmentType;
import com.clinic.healinghouse.repository.TreatmentTypeRepository;

@Service
public class TreatmentTypeService {
	@Autowired
	private TreatmentTypeRepository treatmentTypeRepository;
	
	public TreatmentType findTreatmentTypeById(Integer treatmentTypeId) {
		return treatmentTypeRepository.findById(treatmentTypeId).get();
	}
	
	public TreatmentType saveTreatmentType(TreatmentTypeDTO treatmentTypeDto) {

		TreatmentType treatmentType = new TreatmentType(treatmentTypeDto);
		treatmentType = treatmentTypeRepository.save(treatmentType);

		return treatmentType;
	}

	public List<TreatmentType> getAllTreatmentTypes(){
		return treatmentTypeRepository.findAll();
	}

	public TreatmentType deleteTreatmentType(Integer treatmentTypeId) {
		TreatmentType treatmentType = treatmentTypeRepository.findById(treatmentTypeId).get();
		if(treatmentType!=null) {
			treatmentTypeRepository.delete(treatmentType);
		}
		return treatmentType;
	}

	public TreatmentType updateTreatmentTypeId(TreatmentType treatmentType) {
		
		treatmentType = treatmentTypeRepository.save(treatmentType);

		return treatmentType;
	}
	
}
