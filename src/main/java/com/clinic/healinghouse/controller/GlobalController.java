package com.clinic.healinghouse.controller;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

@Controller
@ControllerAdvice
public class GlobalController {

	@InitBinder
	public void initBinder(WebDataBinder webDataBinder) {
		StringTrimmerEditor ste = new StringTrimmerEditor(false);
		webDataBinder.registerCustomEditor(String.class, ste);
	}
}
