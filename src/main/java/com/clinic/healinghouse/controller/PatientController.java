package com.clinic.healinghouse.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.clinic.healinghouse.repository.PatientRepository;
import com.hh.controller.PatientsController;

@Controller
@RequestMapping("/patient")
public class PatientController {

	private Logger logger = LoggerFactory.getLogger(PatientController.class);
	
	@Autowired
	private PatientRepository patientRepository;
	
	@GetMapping("/newPatientForm")
	public String newPatientForm() {
		
		
		return 
	}
	
}
