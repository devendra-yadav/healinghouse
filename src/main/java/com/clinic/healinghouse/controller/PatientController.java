package com.clinic.healinghouse.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinic.healinghouse.dto.PatientDTO;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.service.PatientService;

import jakarta.validation.Valid;


@Controller
@RequestMapping("/patient")
public class PatientController {

	private Logger logger = LoggerFactory.getLogger(PatientController.class);
	
	@Autowired
	private PatientService patientService;
	
	@GetMapping("/new_patient_form")
	public String newPatientForm(Model model) {
		PatientDTO patient = new PatientDTO();
		model.addAttribute("patient", patient);
		return "patient/new_patient_form";
	}
	
	@PostMapping("/add_patient")
	public String addPatient(@ModelAttribute("patient") @Valid PatientDTO patientDto, BindingResult bindingResult, Model model) {
		logger.info("New request to add patient : "+patientDto);
		if(bindingResult.hasErrors()) {
			logger.warn("Patient have some invalid values"+bindingResult);
			return "patient/new_patient_form";
		}
		
		Patient patient = patientService.savePatient(patientDto);
		logger.info("Patient saved : "+patient);
		
		return "redirect:/patient/view_all_patients?recentlyAddedPatientId="+patient.getId();
	}
	
	@GetMapping("/view_all_patients")
	public String viewAllPatients(@RequestParam(name = "recentlyAddedPatientId", required=false) Integer recentlyAddedPatientId , @RequestParam(name = "recentlyEditedPatientId", required=false) Integer recentlyEditedPatientId, @RequestParam(name = "recentlyDeletedPatientId", required=false) Integer recentlyDeletedPatientId, @RequestParam(name = "recentlyDeletedPatientName", required=false) String recentlyDeletedPatientName, Model model) {
		
		List<Patient> allPatients = patientService.getAllPatients();
		logger.info("Total "+allPatients.size()+" patients fetched!!");
		model.addAttribute("all_patients", allPatients);
		
		if(recentlyAddedPatientId!=null) {
			Patient recentlyAddedPatient = patientService.findPatientById(recentlyAddedPatientId);
			model.addAttribute("recentlyAddedPatient", recentlyAddedPatient);
			logger.info("Recently added patient model var set "+recentlyAddedPatient);
		}
		if(recentlyEditedPatientId!=null) {
			Patient recentlyEditedPatient = patientService.findPatientById(recentlyEditedPatientId);
			model.addAttribute("recentlyEditedPatient", recentlyEditedPatient);
			logger.info("Recently edited patient model var set "+recentlyEditedPatient);
		}
		if(recentlyDeletedPatientId != null) {
			model.addAttribute("recentlyDeletedPatientId", recentlyDeletedPatientId);
			model.addAttribute("recentlyDeletedPatientName", recentlyDeletedPatientName);
			logger.info("Recently deleted patient model var set "+recentlyDeletedPatientId+" "+recentlyDeletedPatientName);
		}
		
		return "patient/view_all_patients";
	}
	
	
	@GetMapping("/delete_patient/{patient_id}")
	public String deletePatient(@PathVariable("patient_id") Integer patientId,  Model model) {
		logger.info("Requet came to delete patient id : "+patientId);
		Patient patient = patientService.deletePatient(patientId);
		if(patient==null) {
			logger.error("Some issue deleting patient id "+patientId);
			return "redirect:/patient/view_all_patients?recentlyDeletedPatientId="+patientId+"&recentlyDeletedPatientName=";
		}
		return "redirect:/patient/view_all_patients?recentlyDeletedPatientId="+patient.getId()+"&recentlyDeletedPatientName="+patient.getName();
	}
	
	
	
}
