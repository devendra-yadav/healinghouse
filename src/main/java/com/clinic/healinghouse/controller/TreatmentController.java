package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.service.TreatmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/services")
@RequiredArgsConstructor
public class TreatmentController {

    private final TreatmentService treatmentService;
    private final TagService tagService;

    @GetMapping
    public String list(@RequestParam(required = false) String tag, Model model) {
        model.addAttribute("services", StringUtils.hasText(tag)
                ? treatmentService.findByTag(tag)
                : treatmentService.findAll());
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("selectedTag", tag);
        model.addAttribute("pageTitle", "Services");
        return "services/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("service", ClinicService.builder().build());
        model.addAttribute("existingTagNames", "");
        model.addAttribute("pageTitle", "New Service");
        return "services/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ClinicService service = treatmentService.getById(id);
        model.addAttribute("service", service);
        model.addAttribute("existingTagNames", joinTagNames(service.getSortedTags()));
        model.addAttribute("pageTitle", "Edit Service");
        return "services/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("service") ClinicService service,
                       BindingResult result,
                       @RequestParam(required = false) String tagNames,
                       Model model,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("existingTagNames", tagNames);
            model.addAttribute("pageTitle", service.getId() == null ? "New Service" : "Edit Service");
            return "services/form";
        }
        treatmentService.save(service, parseTagNames(tagNames));
        ra.addFlashAttribute("successMessage", "Service saved successfully.");
        return "redirect:/services";
    }

    private List<String> parseTagNames(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String joinTagNames(List<Tag> tags) {
        return tags.stream().map(Tag::getName).collect(Collectors.joining(", "));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        treatmentService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Service deactivated successfully.");
        return "redirect:/services";
    }
}