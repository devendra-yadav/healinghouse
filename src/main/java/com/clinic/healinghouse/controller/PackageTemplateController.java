package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.PackageTemplateForm;
import com.clinic.healinghouse.entity.PackageTemplate;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.service.PackageTemplateService;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/package-templates")
@RequiredArgsConstructor
public class PackageTemplateController {

    private final PackageTemplateService packageTemplateService;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final PaginationUtil paginationUtil;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        boolean hasFilter = StringUtils.hasText(q);
        model.addAttribute("templates", (showInactive && !hasFilter)
                ? packageTemplateService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("name")))
                : packageTemplateService.search(q, PageRequest.of(page, pageSize)));
        model.addAttribute("packageTemplateService", packageTemplateService); // for computeOriginalPrice/computeSuggestedPrice in the template
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Package Templates");
        return "package-templates/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("templateForm", new PackageTemplateForm());
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "New Package Template");
        return "package-templates/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        PackageTemplate template = packageTemplateService.getById(id);
        model.addAttribute("templateForm", PackageTemplateForm.from(template));
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "Edit Package Template");
        return "package-templates/form";
    }

    @PostMapping
    public String create(@ModelAttribute("templateForm") PackageTemplateForm form, Model model, RedirectAttributes ra) {
        return saveAndRedirect(form, model, ra);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute("templateForm") PackageTemplateForm form,
                         Model model, RedirectAttributes ra) {
        form.setId(id);
        return saveAndRedirect(form, model, ra);
    }

    private String saveAndRedirect(PackageTemplateForm form, Model model, RedirectAttributes ra) {
        try {
            packageTemplateService.save(form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateCatalogModel(model);
            model.addAttribute("pageTitle", form.getId() == null ? "New Package Template" : "Edit Package Template");
            return "package-templates/form";
        }
        ra.addFlashAttribute("successMessage", "Package template saved successfully.");
        return "redirect:/package-templates";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes ra) {
        packageTemplateService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Package template deactivated successfully.");
        return "redirect:/package-templates";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        packageTemplateService.activate(id);
        ra.addFlashAttribute("successMessage", "Package template reactivated successfully.");
        return "redirect:/package-templates";
    }

    private void populateCatalogModel(Model model) {
        model.addAttribute("allServices", clinicServiceRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("allProducts", productRepository.findByActiveTrueOrderByNameAsc());
    }
}
