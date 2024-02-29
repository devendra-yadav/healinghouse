package com.clinic.healinghouse.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.clinic.healinghouse.dto.PatientDTO;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.repository.PatientRepository;

@Service
public class PatientService {

	@Autowired
	private PatientRepository patientRepository;

	public Patient findPatientById(Integer patientId) {
		return patientRepository.findById(patientId).get();
	}

	public Patient savePatient(PatientDTO patientDto) {

		//howDidYouFindUs in patientDTO contains extra commas. handle them
		String howDidYouFindUs = patientDto.getHowDidYouFindUs();
		if(howDidYouFindUs.endsWith(",")) {
			howDidYouFindUs = howDidYouFindUs.substring(0, howDidYouFindUs.length()-1);
		}
		if(howDidYouFindUs.startsWith(",")) {
			howDidYouFindUs = howDidYouFindUs.substring(1);
		}

		if(howDidYouFindUs.equals(",") || howDidYouFindUs.equals(",,")) {
			howDidYouFindUs = "";
		}
		
		//If repitetive then remove 1 like Instagram, Instagram
		

		patientDto.setHowDidYouFindUs(howDidYouFindUs);

		Patient patient = new Patient(patientDto);
		patient = patientRepository.save(patient);

		return patient;
	}

	public List<Patient> getAllPatients(){
		return patientRepository.findAll();
	}

	public Patient deletePatient(Integer patientId) {
		Patient patient = patientRepository.findById(patientId).get();
		if(patient!=null) {
			patientRepository.delete(patient);
		}
		return patient;
	}

	public Patient updatePatient(Patient patient) {
		//howDidYouFindUs in Patient contains extra commas. handle them
		String howDidYouFindUs = patient.getHowDidYouFindUs();
		if(howDidYouFindUs.endsWith(",")) {
			howDidYouFindUs = howDidYouFindUs.substring(0, howDidYouFindUs.length()-1);
		}
		if(howDidYouFindUs.startsWith(",")) {
			howDidYouFindUs = howDidYouFindUs.substring(1);
		}

		if(howDidYouFindUs.equals(",") || howDidYouFindUs.equals(",,")) {
			howDidYouFindUs = "";
		}

		patient.setHowDidYouFindUs(howDidYouFindUs);
		patient = patientRepository.save(patient);
		
		return patient;
	}

}
