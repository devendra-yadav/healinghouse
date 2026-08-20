package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.CsvImportResultDTO;
import com.clinic.healinghouse.dto.ExportInfoBlock;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.service.TreatmentService;
import com.clinic.healinghouse.util.CsvExportUtil;
import com.clinic.healinghouse.util.PaginationUtil;
import com.clinic.healinghouse.util.PdfExportUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/services")
@RequiredArgsConstructor
public class TreatmentController {

    private final TreatmentService treatmentService;
    private final TagService tagService;
    private final PaginationUtil paginationUtil;
    private final PermissionService permissionService;
    private final CsvExportUtil csvExportUtil;
    private final PdfExportUtil pdfExportUtil;

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String tag,
                       @RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        boolean hasFilter = StringUtils.hasText(q) || StringUtils.hasText(tag);
        model.addAttribute("services", (showInactive && !hasFilter)
                ? treatmentService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("name")))
                : treatmentService.search(q, tag, PageRequest.of(page, pageSize)));
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("selectedTag", tag);
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Services");
        return "services/list";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.VIEW)
    @GetMapping("/export-csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String tag,
                                            @RequestParam(defaultValue = "false") boolean includeInactive) throws java.io.IOException {
        List<ClinicService> rows = exportList(q, tag, includeInactive);
        String csv = csvExportUtil.generateServiceListCsv(rows, buildExportInfoBlocks(q, tag, includeInactive, rows));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=services-" + LocalDate.now() + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.VIEW)
    @GetMapping("/export-pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String tag,
                                            @RequestParam(defaultValue = "false") boolean includeInactive) throws Exception {
        List<ClinicService> rows = exportList(q, tag, includeInactive);
        byte[] pdf = pdfExportUtil.generateServiceListPdf(rows, buildExportInfoBlocks(q, tag, includeInactive, rows));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=services-" + LocalDate.now() + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    /** Mirrors {@link #list}'s own filter precedence, unpaged; {@code includeInactive} then strips
     *  out inactive rows unless explicitly requested — export defaults to active-only. */
    private List<ClinicService> exportList(String q, String tag, boolean includeInactive) {
        List<ClinicService> rows = (StringUtils.hasText(q) || StringUtils.hasText(tag))
                ? treatmentService.search(q, tag, Pageable.unpaged()).getContent()
                : (includeInactive ? treatmentService.findAllIncludingInactive(Pageable.unpaged()).getContent() : treatmentService.findAll());
        return includeInactive ? rows : rows.stream().filter(ClinicService::isActive).toList();
    }

    private List<ExportInfoBlock> buildExportInfoBlocks(String q, String tag, boolean includeInactive, List<ClinicService> rows) {
        long activeCount = rows.stream().filter(ClinicService::isActive).count();
        List<ExportInfoBlock.Line> filterLines = new java.util.ArrayList<>();
        if (StringUtils.hasText(q)) filterLines.add(ExportInfoBlock.line("Search", q));
        if (StringUtils.hasText(tag)) filterLines.add(ExportInfoBlock.line("Tag", tag));
        if (includeInactive) filterLines.add(ExportInfoBlock.line("Status", "Active + Inactive"));
        ExportInfoBlock filters = ExportInfoBlock.ofLines("Filters Applied", filterLines);
        ExportInfoBlock summary = ExportInfoBlock.of("Summary",
                ExportInfoBlock.line("Total Services", String.valueOf(rows.size())),
                ExportInfoBlock.line("Active", String.valueOf(activeCount)),
                ExportInfoBlock.line("Inactive", String.valueOf(rows.size() - activeCount)));
        return java.util.stream.Stream.of(filters, summary).filter(java.util.Objects::nonNull).toList();
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("service", ClinicService.builder().build());
        model.addAttribute("existingTagNames", "");
        model.addAttribute("pageTitle", "New Service");
        return "services/form";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.VIEW)
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ClinicService service = treatmentService.getById(id);
        model.addAttribute("service", service);
        model.addAttribute("pageTitle", service.getName());
        return "services/detail";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.EDIT)
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
        permissionService.require(Module.SERVICES, service.getId() == null ? PermissionAction.CREATE : PermissionAction.EDIT);
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

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        var impact = treatmentService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Service deactivated successfully." + impact);
        return "redirect:/services";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        treatmentService.activate(id);
        ra.addFlashAttribute("successMessage", "Service reactivated successfully.");
        return "redirect:/services";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.APPROVE)
    @PostMapping("/{id}/delete-permanent")
    public String deletePermanent(@PathVariable Long id, RedirectAttributes ra) {
        try {
            treatmentService.permanentlyDelete(id);
            ra.addFlashAttribute("successMessage", "Service permanently deleted.");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/services?showInactive=true";
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.CREATE)
    @GetMapping("/import-csv/template")
    public ResponseEntity<byte[]> importCsvTemplate() {
        String csv = "name,description,durationMinutes,price,tags,active\n"
                + "Deep Tissue Massage,60 minute full body massage,60,1500.00,Commission;Bonus,true\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"services_import_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.SERVICES, action = PermissionAction.CREATE)
    @PostMapping("/import-csv")
    public String importCsv(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Please choose a CSV file to upload.");
            return "redirect:/services";
        }
        try {
            CsvImportResultDTO result = treatmentService.importFromCsv(file);
            ra.addFlashAttribute("importResult", result);
            ra.addFlashAttribute("successMessage", String.format(
                    "Import complete: %d created, %d skipped, %d errors (of %d rows).",
                    result.successCount(), result.skippedCount(), result.errorCount(), result.totalRows()));
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/services";
    }
}