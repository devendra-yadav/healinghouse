package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.RecurringExpenseTemplateForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RecurrenceFrequency;
import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.ExpenseCategoryService;
import com.clinic.healinghouse.service.RecurringExpenseTemplateService;
import com.clinic.healinghouse.service.UserService;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/expenses/recurring")
@RequiredArgsConstructor
public class RecurringExpenseTemplateController {

    private final RecurringExpenseTemplateService recurringExpenseTemplateService;
    private final ExpenseCategoryService expenseCategoryService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final PaginationUtil paginationUtil;

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        model.addAttribute("templates",
                recurringExpenseTemplateService.findAll(PageRequest.of(page, pageSize, Sort.by("label"))));
        model.addAttribute("pageTitle", "Recurring Expense Templates");
        return "expenses/recurring-list";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("templateForm", new RecurringExpenseTemplateForm());
        populateFormModel(model);
        model.addAttribute("pageTitle", "New Recurring Expense Template");
        return "expenses/recurring-form";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        RecurringExpenseTemplate template = recurringExpenseTemplateService.getById(id);
        model.addAttribute("templateForm", RecurringExpenseTemplateForm.from(template));
        populateFormModel(model);
        model.addAttribute("pageTitle", "Edit Recurring Expense Template");
        return "expenses/recurring-form";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.CREATE)
    @PostMapping
    public String create(@ModelAttribute("templateForm") RecurringExpenseTemplateForm form, Model model, RedirectAttributes ra) {
        try {
            recurringExpenseTemplateService.create(form, userService.getById(permissionService.currentUserId()));
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormModel(model);
            model.addAttribute("pageTitle", "New Recurring Expense Template");
            return "expenses/recurring-form";
        }
        ra.addFlashAttribute("successMessage", "Recurring expense template saved successfully.");
        return "redirect:/expenses/recurring";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute("templateForm") RecurringExpenseTemplateForm form,
                         Model model, RedirectAttributes ra) {
        try {
            recurringExpenseTemplateService.update(id, form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormModel(model);
            model.addAttribute("pageTitle", "Edit Recurring Expense Template");
            return "expenses/recurring-form";
        }
        ra.addFlashAttribute("successMessage", "Recurring expense template updated successfully.");
        return "redirect:/expenses/recurring";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @PostMapping("/{id}/pause")
    public String pause(@PathVariable Long id, RedirectAttributes ra) {
        recurringExpenseTemplateService.pause(id);
        ra.addFlashAttribute("successMessage", "Template paused.");
        return "redirect:/expenses/recurring";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @PostMapping("/{id}/resume")
    public String resume(@PathVariable Long id, RedirectAttributes ra) {
        recurringExpenseTemplateService.resume(id);
        ra.addFlashAttribute("successMessage", "Template resumed.");
        return "redirect:/expenses/recurring";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.CREATE)
    @PostMapping("/{id}/generate-now")
    public String generateNow(@PathVariable Long id, RedirectAttributes ra) {
        int generated = recurringExpenseTemplateService.generateNow(id);
        ra.addFlashAttribute("successMessage", generated > 0
                ? "Generated 1 expense from this template."
                : "Template is inactive — nothing generated.");
        return "redirect:/expenses/recurring";
    }

    private void populateFormModel(Model model) {
        model.addAttribute("allCategories", expenseCategoryService.findAllActiveVisible());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("frequencies", RecurrenceFrequency.values());
    }
}
