package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ExportInfoBlock;
import com.clinic.healinghouse.dto.PackageTemplateExportRowDTO;
import com.clinic.healinghouse.dto.PackageTemplateForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PackageTemplate;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.PackageTemplateService;
import com.clinic.healinghouse.util.CsvExportUtil;
import com.clinic.healinghouse.util.PaginationUtil;
import com.clinic.healinghouse.util.PdfExportUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/package-templates")
@RequiredArgsConstructor
public class PackageTemplateController {

    private final PackageTemplateService packageTemplateService;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final HealingHouseProperties properties;
    private final PaginationUtil paginationUtil;
    private final CsvExportUtil csvExportUtil;
    private final PdfExportUtil pdfExportUtil;

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.VIEW)
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

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.VIEW)
    @GetMapping("/export-csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "false") boolean includeInactive) throws java.io.IOException {
        List<PackageTemplateExportRowDTO> rows = exportRows(q, includeInactive);
        String csv = csvExportUtil.generatePackageTemplateListCsv(rows, buildExportInfoBlocks(q, includeInactive, rows));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=package-templates-" + LocalDate.now() + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.VIEW)
    @GetMapping("/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "false") boolean includeInactive) throws Exception {
        List<PackageTemplateExportRowDTO> rows = exportRows(q, includeInactive);
        byte[] pdf = pdfExportUtil.generatePackageTemplateListPdf(rows, buildExportInfoBlocks(q, includeInactive, rows));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=package-templates-" + LocalDate.now() + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    /** Mirrors {@link #list}'s own filter precedence, unpaged; {@code includeInactive} then strips
     *  out inactive rows unless explicitly requested — export defaults to active-only. */
    private List<PackageTemplateExportRowDTO> exportRows(String q, boolean includeInactive) {
        List<PackageTemplate> templates = StringUtils.hasText(q)
                ? packageTemplateService.search(q, Pageable.unpaged()).getContent()
                : (includeInactive ? packageTemplateService.findAllIncludingInactive(Pageable.unpaged()).getContent() : packageTemplateService.findAllActive());
        if (!includeInactive) {
            templates = templates.stream().filter(PackageTemplate::isActive).toList();
        }
        return templates.stream().map(t -> new PackageTemplateExportRowDTO(t.getName(), t.getDescription(),
                packageTemplateService.buildItemsSummary(t), packageTemplateService.computeSuggestedPrice(t), t.isActive())).toList();
    }

    private List<ExportInfoBlock> buildExportInfoBlocks(String q, boolean includeInactive, List<PackageTemplateExportRowDTO> rows) {
        long activeCount = rows.stream().filter(PackageTemplateExportRowDTO::active).count();
        java.math.BigDecimal totalSuggestedPrice = rows.stream().map(PackageTemplateExportRowDTO::suggestedPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        List<ExportInfoBlock.Line> filterLines = new java.util.ArrayList<>();
        if (StringUtils.hasText(q)) filterLines.add(ExportInfoBlock.line("Search", q));
        if (includeInactive) filterLines.add(ExportInfoBlock.line("Status", "Active + Inactive"));
        ExportInfoBlock filters = ExportInfoBlock.ofLines("Filters Applied", filterLines);
        ExportInfoBlock summary = ExportInfoBlock.of("Summary",
                ExportInfoBlock.line("Total Templates", String.valueOf(rows.size())),
                ExportInfoBlock.line("Active", String.valueOf(activeCount)),
                ExportInfoBlock.line("Inactive", String.valueOf(rows.size() - activeCount)),
                ExportInfoBlock.line("Total Suggested Price", formatCurrency(totalSuggestedPrice)));
        return java.util.stream.Stream.of(filters, summary).filter(java.util.Objects::nonNull).toList();
    }

    private String formatCurrency(java.math.BigDecimal value) {
        return properties.getCurrency().getSymbol() + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("templateForm", new PackageTemplateForm());
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "New Package Template");
        return "package-templates/form";
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.VIEW)
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        PackageTemplate template = packageTemplateService.getById(id);
        model.addAttribute("template", template);
        model.addAttribute("packageTemplateService", packageTemplateService); // for computeOriginalPrice/computeSuggestedPrice in the template
        model.addAttribute("pageTitle", template.getName());
        return "package-templates/detail";
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        PackageTemplate template = packageTemplateService.getById(id);
        model.addAttribute("templateForm", PackageTemplateForm.from(template));
        populateCatalogModel(model);
        model.addAttribute("pageTitle", "Edit Package Template");
        return "package-templates/form";
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.CREATE)
    @PostMapping
    public String create(@ModelAttribute("templateForm") PackageTemplateForm form, Model model, RedirectAttributes ra) {
        return saveAndRedirect(form, model, ra);
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.EDIT)
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

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes ra) {
        packageTemplateService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Package template deactivated successfully.");
        return "redirect:/package-templates";
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        packageTemplateService.activate(id);
        ra.addFlashAttribute("successMessage", "Package template reactivated successfully.");
        return "redirect:/package-templates";
    }

    @RequiresPermission(module = Module.PACKAGE_TEMPLATES, action = PermissionAction.APPROVE)
    @PostMapping("/{id}/delete-permanent")
    public String deletePermanent(@PathVariable Long id, RedirectAttributes ra) {
        try {
            packageTemplateService.permanentlyDelete(id);
            ra.addFlashAttribute("successMessage", "Package template permanently deleted.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/package-templates?showInactive=true";
    }

    private void populateCatalogModel(Model model) {
        model.addAttribute("allServices", clinicServiceRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("allProducts", productRepository.findByActiveTrueOrderByNameAsc());
    }
}
