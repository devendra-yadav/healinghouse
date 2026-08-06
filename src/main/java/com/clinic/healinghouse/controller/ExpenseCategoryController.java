package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.ExpenseCategoryForm;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.ExpenseCategoryService;
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
@RequestMapping("/expense-categories")
@RequiredArgsConstructor
public class ExpenseCategoryController {

    private final ExpenseCategoryService expenseCategoryService;
    private final PaginationUtil paginationUtil;

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        boolean hasFilter = StringUtils.hasText(q);
        model.addAttribute("categories", (showInactive && !hasFilter)
                ? expenseCategoryService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("name")))
                : expenseCategoryService.search(q, PageRequest.of(page, pageSize)));
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Expense Categories");
        return "expense-categories/list";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("categoryForm", new ExpenseCategoryForm());
        model.addAttribute("pageTitle", "New Expense Category");
        return "expense-categories/form";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ExpenseCategory category = expenseCategoryService.getById(id);
        model.addAttribute("categoryForm", ExpenseCategoryForm.from(category));
        model.addAttribute("pageTitle", "Edit Expense Category");
        return "expense-categories/form";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.CREATE)
    @PostMapping
    public String create(@ModelAttribute("categoryForm") ExpenseCategoryForm form, Model model, RedirectAttributes ra) {
        return saveAndRedirect(form, model, ra);
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.EDIT)
    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute("categoryForm") ExpenseCategoryForm form,
                         Model model, RedirectAttributes ra) {
        form.setId(id);
        return saveAndRedirect(form, model, ra);
    }

    private String saveAndRedirect(ExpenseCategoryForm form, Model model, RedirectAttributes ra) {
        try {
            expenseCategoryService.save(form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("pageTitle", form.getId() == null ? "New Expense Category" : "Edit Expense Category");
            return "expense-categories/form";
        }
        ra.addFlashAttribute("successMessage", "Expense category saved successfully.");
        return "redirect:/expense-categories";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes ra) {
        expenseCategoryService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Expense category deactivated successfully.");
        return "redirect:/expense-categories";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        expenseCategoryService.activate(id);
        ra.addFlashAttribute("successMessage", "Expense category reactivated successfully.");
        return "redirect:/expense-categories";
    }

    @RequiresPermission(module = Module.EXPENSE_CATEGORIES, action = PermissionAction.APPROVE)
    @PostMapping("/{id}/delete-permanent")
    public String deletePermanent(@PathVariable Long id, RedirectAttributes ra) {
        try {
            expenseCategoryService.permanentlyDelete(id);
            ra.addFlashAttribute("successMessage", "Expense category permanently deleted.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/expense-categories?showInactive=true";
    }
}
