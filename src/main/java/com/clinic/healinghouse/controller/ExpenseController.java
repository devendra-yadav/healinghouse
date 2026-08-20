package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.ExpenseExportFilterDTO;
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
import com.clinic.healinghouse.service.ExpenseCategoryService;
import com.clinic.healinghouse.service.ExpenseService;
import com.clinic.healinghouse.service.UserService;
import com.clinic.healinghouse.util.CsvExportUtil;
import com.clinic.healinghouse.util.PaginationUtil;
import com.clinic.healinghouse.util.PdfExportUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ExpenseCategoryService expenseCategoryService;
    private final UserService userService;
    private final PermissionService permissionService;
    private final PaginationUtil paginationUtil;
    private final CsvExportUtil csvExportUtil;
    private final PdfExportUtil pdfExportUtil;

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
        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom = dateFrom != null ? dateFrom : today.withDayOfMonth(1);
        LocalDate effectiveTo = dateTo != null ? dateTo : today;
        ExpenseStatus status = showVoided ? null : ExpenseStatus.ACTIVE;
        ExpenseFilter filter = new ExpenseFilter(effectiveFrom, effectiveTo, categoryId, vendorName, paymentMethod, status);
        var expenses = expenseService.search(filter, PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "expenseDate")))
                .map(ExpenseListRowDTO::from);
        model.addAttribute("expenses", expenses);
        model.addAttribute("summary", expenseService.getSummary(filter));
        model.addAttribute("categories", expenseCategoryService.findAllActiveVisible());
        model.addAttribute("dateFrom", effectiveFrom);
        model.addAttribute("dateTo", effectiveTo);
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("vendorName", vendorName);
        model.addAttribute("paymentMethod", paymentMethod);
        model.addAttribute("showVoided", showVoided);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("pageTitle", "Expenses");
        return "expenses/list";
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
    @GetMapping("/export-csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String vendorName,
                                            @RequestParam(required = false) PaymentMethod paymentMethod,
                                            @RequestParam(defaultValue = "false") boolean showVoided) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.withDayOfMonth(1);
        LocalDate to = dateTo != null ? dateTo : today;
        ExpenseFilter filter = new ExpenseFilter(from, to, categoryId, vendorName, paymentMethod,
                showVoided ? null : ExpenseStatus.ACTIVE);
        List<ExpenseListRowDTO> rows = expenseService.search(filter, Pageable.unpaged())
                .map(ExpenseListRowDTO::from).getContent();
        var summary = expenseService.getSummary(filter);
        var filterInfo = buildExportFilterInfo(categoryId, vendorName, paymentMethod, showVoided);
        String csv = csvExportUtil.generateExpenseListCsv(rows, from, to, filterInfo, summary);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment;filename=expenses-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
    @GetMapping("/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String vendorName,
                                            @RequestParam(required = false) PaymentMethod paymentMethod,
                                            @RequestParam(defaultValue = "false") boolean showVoided) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.withDayOfMonth(1);
        LocalDate to = dateTo != null ? dateTo : today;
        ExpenseFilter filter = new ExpenseFilter(from, to, categoryId, vendorName, paymentMethod,
                showVoided ? null : ExpenseStatus.ACTIVE);
        List<ExpenseListRowDTO> rows = expenseService.search(filter, Pageable.unpaged())
                .map(ExpenseListRowDTO::from).getContent();
        var summary = expenseService.getSummary(filter);
        var filterInfo = buildExportFilterInfo(categoryId, vendorName, paymentMethod, showVoided);
        byte[] pdf = pdfExportUtil.generateExpenseListPdf(rows, from, to, filterInfo, summary);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment;filename=expenses-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    private ExpenseExportFilterDTO buildExportFilterInfo(Long categoryId, String vendorName,
                                                          PaymentMethod paymentMethod, boolean showVoided) {
        String categoryName = categoryId != null ? expenseCategoryService.getById(categoryId).getName() : null;
        return new ExpenseExportFilterDTO(categoryName, vendorName, paymentMethod, showVoided);
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
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Expense expense = expenseService.getById(id);
        // Mirrors the POST /{id} update's own VOIDED check (ExpenseService.update) — without this,
        // a stale bookmark to a voided expense's edit page rendered a pre-filled form that could
        // never actually be submitted successfully (Bug_Report_v6.md Finding 23).
        if (expense.getStatus() == ExpenseStatus.VOIDED) {
            ra.addFlashAttribute("errorMessage", "A voided expense cannot be edited.");
            return "redirect:/expenses";
        }
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

    private void populateFormModel(Model model) {
        model.addAttribute("allCategories", expenseCategoryService.findAllActiveVisible());
        model.addAttribute("paymentMethods", PaymentMethod.values());
    }
}
