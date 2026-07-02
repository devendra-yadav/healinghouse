package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.service.TherapistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/therapists")
@RequiredArgsConstructor
public class TherapistController {

    private final TherapistService therapistService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("therapists", therapistService.findAll());
        model.addAttribute("pageTitle", "Therapists");
        return "therapists/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("therapist", Therapist.builder().build());
        model.addAttribute("pageTitle", "New Therapist");
        return "therapists/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("therapist", therapistService.getById(id));
        model.addAttribute("pageTitle", "Edit Therapist");
        return "therapists/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("therapist") Therapist therapist,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("pageTitle", therapist.getId() == null ? "New Therapist" : "Edit Therapist");
            return "therapists/form";
        }
        therapistService.save(therapist);
        ra.addFlashAttribute("successMessage", "Therapist saved successfully.");
        return "redirect:/therapists";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        therapistService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Therapist deactivated successfully.");
        return "redirect:/therapists";
    }
}