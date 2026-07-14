package com.clinic.healinghouse.controller;

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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/combos")
@RequiredArgsConstructor
public class ComboController {

    private static final int MAX_SUGGESTIONS = 8;

    private final ComboService comboService;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = PaginationUtil.clampPageSize(size);
        model.addAttribute("combos", comboService.search(q, PageRequest.of(page, pageSize)));
        model.addAttribute("comboService", comboService); // for computeOriginalPrice/computeComboPrice in the template
        model.addAttribute("q", q);
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

    /** JSON autocomplete endpoint backing the combo picker on the appointment form. */
    @GetMapping("/search")
    @ResponseBody
    public List<ComboSuggestionDTO> search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) return List.of();
        return comboService.search(q).stream()
                .limit(MAX_SUGGESTIONS)
                .map(comboService::toSuggestion)
                .toList();
    }

    /** Full combo contents, fetched by the appointment-form picker when staff click "Add". */
    @GetMapping("/{id}/detail")
    @ResponseBody
    public ComboDetailDTO detail(@PathVariable Long id) {
        Combo combo = comboService.getById(id);
        List<ComboDetailDTO.ComboDetailItemDTO> serviceItems = combo.getServiceItems().stream()
                .map(si -> new ComboDetailDTO.ComboDetailItemDTO(si.getService().getId(), si.getQuantity()))
                .toList();
        List<ComboDetailDTO.ComboDetailItemDTO> productItems = combo.getProductItems().stream()
                .map(pi -> new ComboDetailDTO.ComboDetailItemDTO(pi.getProduct().getId(), pi.getQuantity()))
                .toList();
        return new ComboDetailDTO(combo.getId(), combo.getName(),
                combo.getDiscountType() != null ? combo.getDiscountType().name() : "NONE",
                combo.getDiscountValue(), serviceItems, productItems);
    }

    private void populateCatalogModel(Model model) {
        model.addAttribute("allServices", clinicServiceRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("allProducts", productRepository.findByActiveTrueOrderByNameAsc());
    }
}
