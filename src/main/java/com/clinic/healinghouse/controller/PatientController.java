package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.PatientSuggestionDTO;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Gender;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.service.AppointmentService;
import com.clinic.healinghouse.service.PatientHistoryService;
import com.clinic.healinghouse.service.PatientService;
import com.clinic.healinghouse.service.TherapistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/patients")
@RequiredArgsConstructor
public class PatientController {

    private static final int MAX_SUGGESTIONS = 8;

    private final PatientService        patientService;
    private final AppointmentService    appointmentService;
    private final TherapistService      therapistService;
    private final PatientHistoryService patientHistoryService;

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("patients", patientService.search(q));
        model.addAttribute("q", q);
        model.addAttribute("pageTitle", "Patients");
        return "patients/list";
    }

    /** JSON autocomplete endpoint backing the name/phone search box on the patients list. */
    @GetMapping("/search")
    @ResponseBody
    public List<PatientSuggestionDTO> search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return List.of();
        return patientService.search(q).stream()
                .limit(MAX_SUGGESTIONS)
                .map(p -> new PatientSuggestionDTO(p.getId(), p.getFullName(), p.getPhone()))
                .toList();
    }

    // ── Detail (profile + summary stats + filterable appointment history) ──
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) Long therapistId,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                         Model model, RedirectAttributes ra) {
        try {
            Patient patient = patientService.getById(id);

            AppointmentStatus statusEnum = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusEnum = AppointmentStatus.valueOf(status.trim());
                } catch (IllegalArgumentException ignored) {}
            }

            model.addAttribute("patient", patient);
            model.addAttribute("summary", patientHistoryService.summarize(patient));
            model.addAttribute("appointments",
                    appointmentService.findByFilters(statusEnum, therapistId, dateFrom, dateTo, null, id));
            model.addAttribute("therapists",      therapistService.findAll());
            model.addAttribute("statuses",        AppointmentStatus.values());
            model.addAttribute("selectedStatus",  status);
            model.addAttribute("selectedTherapistId", therapistId);
            model.addAttribute("selectedDateFrom", dateFrom);
            model.addAttribute("selectedDateTo",   dateTo);
            model.addAttribute("pageTitle", "Patient — " + patient.getFullName());
            return "patients/detail";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load patient: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/patients";
        }
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