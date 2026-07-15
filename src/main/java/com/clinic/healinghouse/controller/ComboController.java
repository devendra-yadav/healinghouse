package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ComboDetailDTO;
import com.clinic.healinghouse.dto.ComboForm;
import com.clinic.healinghouse.dto.ComboSuggestionDTO;
import com.clinic.healinghouse.entity.Combo;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.service.ComboService;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/combos")
@RequiredArgsConstructor
public class ComboController {

    private final ComboService comboService;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final HealingHouseProperties properties;
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
        model.addAttribute("combos", (showInactive && !hasFilter)
                ? comboService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("name")))
                : comboService.search(q, PageRequest.of(page, pageSize)));
        model.addAttribute("comboService", comboService); // for computeOriginalPrice/computeComboPrice in the template
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Combos");
        return "combos/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("comboForm", new ComboForm());
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "New Combo");
        return "combos/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Combo combo = comboService.getById(id);
        model.addAttribute("comboForm", ComboForm.from(combo));
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "Edit Combo");
        return "combos/form";
    }

    @PostMapping
    public String create(@ModelAttribute("comboForm") ComboForm form, Model model, RedirectAttributes ra) {
        return saveAndRedirect(form, model, ra);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute("comboForm") ComboForm form,
                         Model model, RedirectAttributes ra) {
        form.setId(id);
        return saveAndRedirect(form, model, ra);
    }

    private String saveAndRedirect(ComboForm form, Model model, RedirectAttributes ra) {
        try {
            comboService.save(form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateCatalogModel(model);
            model.addAttribute("pageTitle", form.getId() == null ? "New Combo" : "Edit Combo");
            return "combos/form";
        }
        ra.addFlashAttribute("successMessage", "Combo saved successfully.");
        return "redirect:/combos";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes ra) {
        comboService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Combo deactivated successfully.");
        return "redirect:/combos";
    }

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        comboService.activate(id);
        ra.addFlashAttribute("successMessage", "Combo reactivated successfully.");
        return "redirect:/combos";
    }

    @PostMapping("/{id}/delete-permanent")
    public String deletePermanent(@PathVariable Long id, RedirectAttributes ra) {
        try {
            comboService.permanentlyDelete(id);
            ra.addFlashAttribute("successMessage", "Combo permanently deleted.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/combos?showInactive=true";
    }

    /** JSON autocomplete endpoint backing the combo picker on the appointment form. */
    @GetMapping("/search")
    @ResponseBody
    public List<ComboSuggestionDTO> search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return List.of();
        return comboService.search(q).stream()
                .limit(properties.getAutocomplete().getComboMaxSuggestions())
                .map(comboService::toSuggestion)
                .toList();
    }

    /** Full combo contents, fetched by the appointment-form picker when staff click "Add". */
    @GetMapping("/{id}/detail")
    @ResponseBody
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Combo combo;
        try {
            combo = comboService.getById(id);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(java.util.Map.of("error", "Combo not found or no longer available."));
        }
        if (!comboService.isSelectable(combo)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("error", "This combo is no longer available (one of its items was deactivated)."));
        }
        List<ComboDetailDTO.ComboDetailItemDTO> serviceItems = combo.getServiceItems().stream()
                .map(si -> new ComboDetailDTO.ComboDetailItemDTO(si.getService().getId(), si.getQuantity(), si.getService().getPrice()))
                .toList();
        List<ComboDetailDTO.ComboDetailItemDTO> productItems = combo.getProductItems().stream()
                .map(pi -> new ComboDetailDTO.ComboDetailItemDTO(pi.getProduct().getId(), pi.getQuantity(), pi.getProduct().getPrice()))
                .toList();
        return ResponseEntity.ok(new ComboDetailDTO(combo.getId(), combo.getName(),
                combo.getDiscountType() != null ? combo.getDiscountType().name() : "NONE",
                combo.getDiscountValue(), serviceItems, productItems));
    }

    private void populateCatalogModel(Model model) {
        model.addAttribute("allServices", clinicServiceRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("allProducts", productRepository.findByActiveTrueOrderByNameAsc());
    }
}
