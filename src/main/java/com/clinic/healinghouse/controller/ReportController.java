package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.RevenueReportDTO;
import com.clinic.healinghouse.dto.RevenueReportFilter;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.service.ProductService;
import com.clinic.healinghouse.service.ReportService;
import com.clinic.healinghouse.service.TagService;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.service.TreatmentService;
import com.clinic.healinghouse.util.CsvExportUtil;
import com.clinic.healinghouse.util.PdfExportUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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

    private static final int DEFAULT_RANGE_DAYS = 30;

    private final ReportService reportService;
    private final TherapistService therapistService;
    private final TreatmentService treatmentService;
    private final ProductService productService;
    private final TagService tagService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("pageTitle", "Reports");
        return "reports/index";
    }

    @GetMapping("/daily")
    public String daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         Model model) {
        LocalDate selectedDate = date != null ? date : LocalDate.now();

        model.addAttribute("pageTitle", "Daily Report");
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("report", reportService.getDailyReport(selectedDate));
        return "reports/daily";
    }

    @GetMapping("/period")
    public String period(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                          Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Period Report");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getPeriodReport(from, to));
        return "reports/period";
    }

    @GetMapping("/comparison")
    public String comparison(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                              @RequestParam(required = false) List<Long> therapistIds,
                              Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
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

    @GetMapping("/patients")
    public String patients(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                            Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Patient Acquisition");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getPatientAcquisitionReport(from, to));
        return "reports/patients";
    }

    @GetMapping("/performance")
    public String performance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                               Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        model.addAttribute("pageTitle", "Product/Service Performance");
        model.addAttribute("selectedDateFrom", from);
        model.addAttribute("selectedDateTo", to);
        model.addAttribute("report", reportService.getProductPerformanceReport(from, to));
        return "reports/performance";
    }

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
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        RevenueReportDTO report = reportService.getRevenueReport(filter, PageRequest.of(page, size));

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
        return AppointmentStatus.valueOf(status.trim());
    }

    @GetMapping("/daily/export-csv")
    public ResponseEntity<byte[]> exportDailyReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws IOException {
        LocalDate selectedDate = date != null ? date : LocalDate.now();
        var report = reportService.getDailyReport(selectedDate);
        String csv = CsvExportUtil.generateDailyReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=daily-report-" + selectedDate + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/daily/export-pdf")
    public ResponseEntity<byte[]> exportDailyReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws Exception {
        LocalDate selectedDate = date != null ? date : LocalDate.now();
        var report = reportService.getDailyReport(selectedDate);
        byte[] pdf = PdfExportUtil.generateDailyReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=daily-report-" + selectedDate + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @GetMapping("/period/export-csv")
    public ResponseEntity<byte[]> exportPeriodReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPeriodReport(from, to);
        String csv = CsvExportUtil.generatePeriodReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=period-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/period/export-pdf")
    public ResponseEntity<byte[]> exportPeriodReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPeriodReport(from, to);
        byte[] pdf = PdfExportUtil.generatePeriodReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=period-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @GetMapping("/comparison/export-csv")
    public ResponseEntity<byte[]> exportComparisonReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> therapistIds) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;
        List<Long> selectedIds = therapistIds != null && !therapistIds.isEmpty() ? therapistIds : List.of();

        if (selectedIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        var report = reportService.getTherapistComparison(selectedIds, from, to);
        String csv = CsvExportUtil.generateComparisonReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=comparison-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/comparison/export-pdf")
    public ResponseEntity<byte[]> exportComparisonReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> therapistIds) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;
        List<Long> selectedIds = therapistIds != null && !therapistIds.isEmpty() ? therapistIds : List.of();

        if (selectedIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        var report = reportService.getTherapistComparison(selectedIds, from, to);
        byte[] pdf = PdfExportUtil.generateComparisonReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=comparison-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @GetMapping("/patients/export-csv")
    public ResponseEntity<byte[]> exportPatientReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPatientAcquisitionReport(from, to);
        String csv = CsvExportUtil.generatePatientReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=patient-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/patients/export-pdf")
    public ResponseEntity<byte[]> exportPatientReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getPatientAcquisitionReport(from, to);
        byte[] pdf = PdfExportUtil.generatePatientReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=patient-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    @GetMapping("/performance/export-csv")
    public ResponseEntity<byte[]> exportPerformanceReportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getProductPerformanceReport(from, to);
        String csv = CsvExportUtil.generatePerformanceReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=performance-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/performance/export-pdf")
    public ResponseEntity<byte[]> exportPerformanceReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        var report = reportService.getProductPerformanceReport(from, to);
        byte[] pdf = PdfExportUtil.generatePerformanceReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=performance-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

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
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        var report = reportService.getRevenueReport(filter, Pageable.unpaged());
        String csv = CsvExportUtil.generateRevenueReportCsv(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=revenue-report-" + from + "-to-" + to + ".csv")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

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
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(DEFAULT_RANGE_DAYS - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        RevenueReportFilter filter = new RevenueReportFilter(from, to, therapistId, patientName, serviceId, productId,
                tagName, paymentMethod, resolveDrilldownStatus(status), discountedOnly);
        var report = reportService.getRevenueReport(filter, Pageable.unpaged());
        byte[] pdf = PdfExportUtil.generateRevenueReportPdf(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=revenue-report-" + from + "-to-" + to + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }
}
