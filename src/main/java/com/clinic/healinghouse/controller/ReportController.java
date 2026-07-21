package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.RevenueReportDTO;
import com.clinic.healinghouse.dto.RevenueReportFilter;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.ProductService;
import com.clinic.healinghouse.service.ReportService;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.service.TreatmentService;
import com.clinic.healinghouse.util.CsvExportUtil;
import com.clinic.healinghouse.util.PaginationUtil;
import com.clinic.healinghouse.util.PdfExportUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final TherapistService therapistService;
    private final TreatmentService treatmentService;
    private final HealingHouseProperties properties;
    private final PaginationUtil paginationUtil;
    private final CsvExportUtil csvExportUtil;
    private final PdfExportUtil pdfExportUtil;
    private final ProductService productService;
    private final TagService tagService;
    private final PermissionService permissionService;

    /** Daily/period/comparison/patients/performance are clinic-wide aggregates — every therapist's
     *  revenue and commission, not just the caller's own. THERAPIST no longer holds REPORTS_STANDARD
     *  at all (Reports is Owner/Admin/Receptionist only), so these endpoints are already unreachable
     *  for that role via the coarse module gate — this check is defense in depth in case a future
     *  Access Matrix edit re-grants REPORTS_STANDARD to THERAPIST without also scoping it. A
     *  THERAPIST's own earnings are already served by {@code GET /therapists/{id}} via CommissionCalculator. */
    private void denyClinicWideReportsForTherapist() {
        if (permissionService.currentTherapistId() != null) {
            throw new AccessDeniedException("This report isn't available for your role.");
        }
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping
    public String index(Model model) {
        model.addAttribute("pageTitle", "Reports");
        return "reports/index";
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping("/daily")
    public String daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         Model model) {
        denyClinicWideReportsForTherapist();
        LocalDate selectedDate = date != null ? date : LocalDate.now();

        model.addAttribute("pageTitle", "Daily Report");
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("report", reportService.getDailyReport(selectedDate));
        return "reports/daily";
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping("/period")
    public String period(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                          Model model) {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Period Report");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getPeriodReport(from, to));
        return "reports/period";
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping("/comparison")
    public String comparison(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                              @RequestParam(required = false) List<Long> therapistIds,
                              Model model) {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;
        List<Long> selectedIds = therapistIds != null ? therapistIds : List.of();

        model.addAttribute("pageTitle", "Therapist Comparison");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("allTherapists", therapistService.findAll());
        model.addAttribute("selectedTherapistIds", selectedIds);

        if (selectedIds.size() >= 2) {
            model.addAttribute("report", reportService.getTherapistComparison(selectedIds, from, to));
        }
        return "reports/comparison";
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping("/patients")
    public String patients(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                            Model model) {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Patient Acquisition");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getPatientAcquisitionReport(from, to));
        return "reports/patients";
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.VIEW)
    @GetMapping("/performance")
    public String performance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                               Model model) {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Product/Service Performance");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getProductPerformanceReport(from, to));
        return "reports/performance";
    }

    @RequiresPermission(module = Module.REPORTS_REVENUE, action = PermissionAction.VIEW)
    @GetMapping("/revenue")
    public String revenue(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                           @RequestParam(required = false) Long therapistId,
                           @RequestParam(required = false) String patientName,
                           @RequestParam(required = false) Long serviceId,
                           @RequestParam(required = false) Long productId,
                           @RequestParam(required = false) String tagName,
                           @RequestParam(required = false) PaymentMethod paymentMethod,
                           @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "false") boolean discountedOnly,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        RevenueReportDTO report = reportService.getRevenueReport(filter, PageRequest.of(page, pageSize));

        model.addAttribute("pageTitle", "Actual Revenue");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("allTherapists", therapistService.findAll());
        model.addAttribute("allServices", treatmentService.findAll());
        model.addAttribute("allProducts", productService.findAll());
        model.addAttribute("allTags", tagService.findAll());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("statuses", AppointmentStatus.values());
        model.addAttribute("selectedTherapistId", therapistId);
        model.addAttribute("selectedPatientName", patientName);
        model.addAttribute("selectedServiceId", serviceId);
        model.addAttribute("selectedProductId", productId);
        model.addAttribute("selectedTagName", tagName);
        model.addAttribute("selectedPaymentMethod", paymentMethod);
        model.addAttribute("selectedStatus", status != null ? status : "COMPLETED");
        model.addAttribute("selectedDiscountedOnly", discountedOnly);
        model.addAttribute("report", report);
        return "reports/revenue";
    }

    /** "" / null → default to Completed-only; "ALL" → no status filter (every status shown); otherwise the named status. */
    private static AppointmentStatus resolveDrilldownStatus(String status) {
        if (status == null || status.isBlank()) return AppointmentStatus.COMPLETED;
        if ("ALL".equalsIgnoreCase(status.trim())) return null;
        try {
            return AppointmentStatus.valueOf(status.trim());
        } catch (IllegalArgumentException ignored) {
            return AppointmentStatus.COMPLETED;
        }
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/daily/export-csv")
    public ResponseEntity<byte[]> exportDailyReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        denyClinicWideReportsForTherapist();
        LocalDate selectedDate = date != null ? date : LocalDate.now();
        var report = reportService.getDailyReport(selectedDate);
        String csv = csvExportUtil.generateDailyReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=daily-report-" + selectedDate + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/daily/export-pdf")
    public ResponseEntity<byte[]> exportDailyReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws Exception {
        denyClinicWideReportsForTherapist();
        LocalDate selectedDate = date != null ? date : LocalDate.now();
        var report = reportService.getDailyReport(selectedDate);
        byte[] pdf = pdfExportUtil.generateDailyReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=daily-report-" + selectedDate + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/period/export-csv")
    public ResponseEntity<byte[]> exportPeriodReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPeriodReport(from, to);
        String csv = csvExportUtil.generatePeriodReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=period-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/period/export-pdf")
    public ResponseEntity<byte[]> exportPeriodReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPeriodReport(from, to);
        byte[] pdf = pdfExportUtil.generatePeriodReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=period-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/comparison/export-csv")
    public ResponseEntity<byte[]> exportComparisonReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> therapistIds) throws IOException {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;
        List<Long> selectedIds = therapistIds != null && !therapistIds.isEmpty() ? therapistIds : List.of();

        if (selectedIds.size() < 2) {
            return ResponseEntity.badRequest().build();
        }

        var report = reportService.getTherapistComparison(selectedIds, from, to);
        String csv = csvExportUtil.generateComparisonReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=comparison-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/comparison/export-pdf")
    public ResponseEntity<byte[]> exportComparisonReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> therapistIds) throws Exception {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;
        List<Long> selectedIds = therapistIds != null && !therapistIds.isEmpty() ? therapistIds : List.of();

        if (selectedIds.size() < 2) {
            return ResponseEntity.badRequest().build();
        }

        var report = reportService.getTherapistComparison(selectedIds, from, to);
        byte[] pdf = pdfExportUtil.generateComparisonReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=comparison-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/patients/export-csv")
    public ResponseEntity<byte[]> exportPatientReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPatientAcquisitionReport(from, to);
        String csv = csvExportUtil.generatePatientReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=patient-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/patients/export-pdf")
    public ResponseEntity<byte[]> exportPatientReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPatientAcquisitionReport(from, to);
        byte[] pdf = pdfExportUtil.generatePatientReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=patient-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/performance/export-csv")
    public ResponseEntity<byte[]> exportPerformanceReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getProductPerformanceReport(from, to);
        String csv = csvExportUtil.generatePerformanceReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=performance-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_STANDARD, action = PermissionAction.EXPORT)
    @GetMapping("/performance/export-pdf")
    public ResponseEntity<byte[]> exportPerformanceReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        denyClinicWideReportsForTherapist();
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getProductPerformanceReport(from, to);
        byte[] pdf = pdfExportUtil.generatePerformanceReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=performance-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @RequiresPermission(module = Module.REPORTS_REVENUE, action = PermissionAction.EXPORT)
    @GetMapping("/revenue/export-csv")
    public ResponseEntity<byte[]> exportRevenueReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long therapistId,
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String tagName,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean discountedOnly) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        var report = reportService.getRevenueReport(filter, Pageable.unpaged());
        String csv = csvExportUtil.generateRevenueReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=revenue-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @RequiresPermission(module = Module.REPORTS_REVENUE, action = PermissionAction.EXPORT)
    @GetMapping("/revenue/export-pdf")
    public ResponseEntity<byte[]> exportRevenueReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long therapistId,
            @RequestParam(required = false) String patientName,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String tagName,
            @RequestParam(required = false) PaymentMethod paymentMethod,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean discountedOnly) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        var report = reportService.getRevenueReport(filter, Pageable.unpaged());
        byte[] pdf = pdfExportUtil.generateRevenueReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=revenue-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }
}
