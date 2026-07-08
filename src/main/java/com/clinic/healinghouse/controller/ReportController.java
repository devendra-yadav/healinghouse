package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.service.ReportService;
import com.clinic.healinghouse.service.TherapistService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final int DEFAULT_RANGE_DAYS = 30;

    private final ReportService reportService;
    private final TherapistService therapistService;

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
}
