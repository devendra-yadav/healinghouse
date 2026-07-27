package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.ExpenseFilter;
import com.clinic.healinghouse.dto.ExpenseForm;
import com.clinic.healinghouse.dto.ExpenseListRowDTO;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.CommissionCalculator;
import com.clinic.healinghouse.service.ExpenseCategoryService;
import com.clinic.healinghouse.service.ExpenseService;
import com.clinic.healinghouse.service.UserService;
import com.clinic.healinghouse.repository.TherapistRepository;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

@Controller
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ExpenseCategoryService expenseCategoryService;
    private final TherapistRepository therapistRepository;
    private final CommissionCalculator commissionCalculator;
    private final UserService userService;
    private final PermissionService permissionService;
    private final PaginationUtil paginationUtil;

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(required = false) String vendorName,
                       @RequestParam(required = false) PaymentMethod paymentMethod,
                       @RequestParam(defaultValue = "false") boolean showVoided,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        ExpenseStatus status = showVoided ? null : ExpenseStatus.ACTIVE;
        ExpenseFilter filter = new ExpenseFilter(dateFrom, dateTo, categoryId, vendorName, paymentMethod, status);
        var expenses = expenseService.search(filter, PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "expenseDate")))
                .map(ExpenseListRowDTO::from);
        model.addAttribute("expenses", expenses);
        model.addAttribute("categories", expenseCategoryService.findAllActiveVisible());
        model.addAttribute("dateFrom", dateFrom);
        model.addAttribute("dateTo", dateTo);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("vendorName", vendorName);
        model.addAttribute("paymentMethod", paymentMethod);
        model.addAttribute("showVoided", showVoided);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("pageTitle", "Expenses");
        return "expenses/list";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("expenseForm", new ExpenseForm());
        populateFormModel(model);
        model.addAttribute("pageTitle", "New Expense");
        return "expenses/form";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Expense expense = expenseService.getById(id);
        model.addAttribute("expenseForm", ExpenseForm.from(expense));
        populateFormModel(model);
        model.addAttribute("pageTitle", "Edit Expense");
        return "expenses/form";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.CREATE)
    @PostMapping
    public String create(@ModelAttribute("expenseForm") ExpenseForm form, Model model, RedirectAttributes ra) {
        try {
            expenseService.create(form, userService.getById(permissionService.currentUserId()));
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormModel(model);
            model.addAttribute("pageTitle", "New Expense");
            return "expenses/form";
        }
        ra.addFlashAttribute("successMessage", "Expense recorded successfully.");
        return "redirect:/expenses";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.EDIT)
    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute("expenseForm") ExpenseForm form,
                         Model model, RedirectAttributes ra) {
        try {
            expenseService.update(id, form);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            populateFormModel(model);
            model.addAttribute("pageTitle", "Edit Expense");
            return "expenses/form";
        }
        ra.addFlashAttribute("successMessage", "Expense updated successfully.");
        return "redirect:/expenses";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/void")
    public String voidExpense(@PathVariable Long id, RedirectAttributes ra) {
        expenseService.voidExpense(id);
        ra.addFlashAttribute("successMessage", "Expense voided successfully.");
        return "redirect:/expenses";
    }

    /** Read-only reference figure for the Salaries & Commission category form (§5.4) — never
     *  bound into the amount field, purely informational. */
    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
    @GetMapping("/commission-suggestion")
    @ResponseBody
    public BigDecimal commissionSuggestion(@RequestParam Long therapistId,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        var therapist = therapistRepository.findById(therapistId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Therapist not found: " + therapistId));
        return commissionCalculator.calculateEarnings(therapist, dateFrom, dateTo).totalVariablePay();
    }

    private void populateFormModel(Model model) {
        model.addAttribute("allCategories", expenseCategoryService.findAllActiveVisible());
        model.addAttribute("allTherapists", therapistRepository.findByActiveTrueOrderByFullNameAsc());
        model.addAttribute("paymentMethods", PaymentMethod.values());
    }
}
