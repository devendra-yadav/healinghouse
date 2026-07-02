package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.service.TreatmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/services")
@RequiredArgsConstructor
public class TreatmentController {

    static final List<String> CATEGORIES = List.of(
            "Massage", "Acupuncture", "TCM", "Detox", "IonTherapy", "Compression", "Hijama", "Other"
    );

    private final TreatmentService treatmentService;

    @GetMapping
    public String list(@RequestParam(required = false) String category, Model model) {
        model.addAttribute("services", StringUtils.hasText(category)
                ? treatmentService.findByCategory(category)
                : treatmentService.findAll());
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("pageTitle", "Services");
        return "services/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("service", ClinicService.builder().build());
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("pageTitle", "New Service");
        return "services/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("service", treatmentService.getById(id));
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("pageTitle", "Edit Service");
        return "services/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("service") ClinicService service,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("categories", CATEGORIES);
            model.addAttribute("pageTitle", service.getId() == null ? "New Service" : "Edit Service");
            return "services/form";
        }
        treatmentService.save(service);
        ra.addFlashAttribute("successMessage", "Service saved successfully.");
        return "redirect:/services";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        treatmentService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Service deactivated successfully.");
        return "redirect:/services";
    }
}