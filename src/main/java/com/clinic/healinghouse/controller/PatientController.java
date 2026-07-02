package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Gender;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("patients", patientService.search(q));
        model.addAttribute("q", q);
        model.addAttribute("pageTitle", "Patients");
        return "patients/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("patient", Patient.builder().build());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("pageTitle", "New Patient");
        return "patients/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("patient", patientService.getById(id));
        model.addAttribute("genders", Gender.values());
        model.addAttribute("pageTitle", "Edit Patient");
        return "patients/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("patient") Patient patient,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("genders", Gender.values());
            model.addAttribute("pageTitle", patient.getId() == null ? "New Patient" : "Edit Patient");
            return "patients/form";
        }
        patientService.save(patient);
        ra.addFlashAttribute("successMessage", "Patient saved successfully.");
        return "redirect:/patients";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        patientService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Patient deactivated successfully.");
        return "redirect:/patients";
    }
}