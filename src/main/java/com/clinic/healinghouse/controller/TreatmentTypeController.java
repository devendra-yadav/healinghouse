package com.clinic.healinghouse.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinic.healinghouse.dto.TreatmentTypeDTO;
import com.clinic.healinghouse.entity.TreatmentType;
import com.clinic.healinghouse.service.TreatmentTypeService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/treatment_type")
public class TreatmentTypeController {
	
	private Logger logger = LoggerFactory.getLogger(TreatmentTypeController.class);
	
	@Autowired
	private TreatmentTypeService treatmentTypeService;
	
	@GetMapping("/new_treatment_type_form")
	public String newTreatmentTypeForm(@RequestParam(name = "recentlyAddedTreatmentTypeId", required = false) Integer recentlyAddedTreatmentTypeId  , Model model) {
		TreatmentTypeDTO treatmentTypeDto = new TreatmentTypeDTO();
		
		if(recentlyAddedTreatmentTypeId!=null) {
			TreatmentType treatmentType = treatmentTypeService.findTreatmentTypeById(recentlyAddedTreatmentTypeId);
			model.addAttribute("recentlyAddedTreatmentType", treatmentType);
		}
		
		model.addAttribute("treatmentType", treatmentTypeDto);
		
		return "/treatment_type/new_treatment_type_form";
		
	}
	
	@PostMapping("/add_treatment_type")
	public String addTreatmentType(@ModelAttribute("treatmentType") @Valid TreatmentTypeDTO treatmentTypeDto, BindingResult bindingResult, Model model) {
		logger.info("Request came to add new treatment type "+treatmentTypeDto);
		if(bindingResult.hasErrors()) {
			logger.warn("invalid values sent for new treatment type. "+bindingResult);
			
			return "/treatment_type/new_treatment_type_form";
		}
		TreatmentType treatmentType =   treatmentTypeService.saveTreatmentType(treatmentTypeDto);
		logger.info("new treatment type saved "+treatmentType);
				
		return "redirect:/treatment_type/new_treatment_type_form?recentlyAddedTreatmentTypeId="+treatmentType.getId();
	}

}
