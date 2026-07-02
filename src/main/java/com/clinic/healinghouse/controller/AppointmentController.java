package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final PatientService     patientService;
    private final TherapistService   therapistService;
    private final TreatmentService   treatmentService;
    private final ProductService     productService;

    // ── List ──────────────────────────────────────────────────────────────
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) Long therapistId,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                       @RequestParam(required = false) String patientName,
                       Model model) {

        AppointmentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = AppointmentStatus.valueOf(status.trim());
            } catch (IllegalArgumentException ignored) {}
        }

        List<Appointment> appointments =
                appointmentService.findByFilters(statusEnum, therapistId, dateFrom, dateTo, patientName);

        model.addAttribute("appointments",       appointments);
        model.addAttribute("therapists",         therapistService.findAll());
        model.addAttribute("statuses",           AppointmentStatus.values());
        model.addAttribute("selectedStatus",     status);
        model.addAttribute("selectedTherapistId",therapistId);
        model.addAttribute("selectedDateFrom",   dateFrom);
        model.addAttribute("selectedDateTo",     dateTo);
        model.addAttribute("selectedPatientName",patientName);
        model.addAttribute("pageTitle", "Appointments");
        return "appointments/list";
    }

    // ── New form ──────────────────────────────────────────────────────────
    @GetMapping("/new")
    public String newForm(Model model) {
        populateFormModel(model);
        model.addAttribute("form", new AppointmentForm());
        model.addAttribute("editMode",   false);
        model.addAttribute("formAction", "/appointments/save");
        model.addAttribute("cancelUrl",  "/appointments");
        model.addAttribute("pageTitle",  "New Appointment");
        return "appointments/form";
    }

    // ── Save (create) ─────────────────────────────────────────────────────
    @PostMapping("/save")
    public String save(@ModelAttribute("form") AppointmentForm form,
                       RedirectAttributes ra) {
        try {
            Appointment saved = appointmentService.createAppointment(form);
            ra.addFlashAttribute("successMessage",
                    "Appointment #" + saved.getId() + " created successfully.");
            return "redirect:/appointments/" + saved.getId();
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to save appointment. Please try again.";
            ra.addFlashAttribute("errorMessage", msg);
            return "redirect:/appointments/new";
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        try {
            model.addAttribute("appointment", appointmentService.getById(id));
            model.addAttribute("pageTitle", "Appointment Details");
            return "appointments/detail";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load appointment: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/appointments";
        }
    }

    // ── Edit form ─────────────────────────────────────────────────────────
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        try {
            Appointment appt = appointmentService.getById(id);
            populateFormModel(model);

            List<Map<String, Object>> existingServiceLines = appt.getServiceLines().stream()
                    .map(sl -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("serviceId", sl.getService().getId());
                        m.put("quantity",  sl.getQuantity());
                        return m;
                    }).toList();
            List<Map<String, Object>> existingProductLines = appt.getProductLines().stream()
                    .map(pl -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("productId", pl.getProduct().getId());
                        m.put("quantity",  pl.getQuantity());
                        return m;
                    }).toList();

            model.addAttribute("appointment",          appt);
            model.addAttribute("form",                 AppointmentForm.from(appt));
            model.addAttribute("existingServiceLines", existingServiceLines);
            model.addAttribute("existingProductLines", existingProductLines);
            model.addAttribute("editMode",   true);
            model.addAttribute("formAction", "/appointments/" + id + "/update");
            model.addAttribute("cancelUrl",  "/appointments/" + id);
            model.addAttribute("pageTitle",  "Edit Appointment #" + id);
            return "appointments/form";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load appointment: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/appointments";
        }
    }

    // ── Update (edit save) ────────────────────────────────────────────────
    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @ModelAttribute("form") AppointmentForm form,
                         RedirectAttributes ra) {
        try {
            appointmentService.updateAppointment(id, form);
            ra.addFlashAttribute("successMessage", "Appointment #" + id + " updated successfully.");
            return "redirect:/appointments/" + id;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to update appointment. Please try again.";
            ra.addFlashAttribute("errorMessage", msg);
            return "redirect:/appointments/" + id + "/edit";
        }
    }

    // ── Status transitions ────────────────────────────────────────────────
    @PostMapping("/{id}/complete")
    public String complete(@PathVariable Long id,
                           @RequestParam(defaultValue = "") String returnUrl,
                           RedirectAttributes ra) {
        try {
            appointmentService.markAsCompleted(id);
            ra.addFlashAttribute("successMessage", "Appointment marked as completed.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + (returnUrl.isBlank() ? "/appointments/" + id : returnUrl);
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(defaultValue = "") String cancelReason,
                         @RequestParam(defaultValue = "") String returnUrl,
                         RedirectAttributes ra) {
        try {
            appointmentService.cancelAppointment(id, cancelReason);
            ra.addFlashAttribute("successMessage", "Appointment cancelled.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + (returnUrl.isBlank() ? "/appointments/" + id : returnUrl);
    }

    @PostMapping("/{id}/no-show")
    public String noShow(@PathVariable Long id,
                         @RequestParam(defaultValue = "") String returnUrl,
                         RedirectAttributes ra) {
        try {
            appointmentService.markAsNoShow(id);
            ra.addFlashAttribute("successMessage", "Appointment marked as no-show.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + (returnUrl.isBlank() ? "/appointments/" + id : returnUrl);
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private void populateFormModel(Model model) {
        List<Map<String, Object>> serviceData = treatmentService.findAll().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",              s.getId());
                    m.put("name",            s.getName());
                    m.put("price",           s.getPrice() != null ? s.getPrice().doubleValue() : 0.0);
                    m.put("durationMinutes", s.getDurationMinutes());
                    return m;
                }).toList();

        List<Map<String, Object>> productData = productService.findAll().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",    p.getId());
                    m.put("name",  p.getName());
                    m.put("price", p.getPrice() != null ? p.getPrice().doubleValue() : 0.0);
                    m.put("stock", p.getStockQuantity());
                    return m;
                }).toList();

        model.addAttribute("patients",       patientService.findAll());
        model.addAttribute("therapists",     therapistService.findAll());
        model.addAttribute("serviceData",    serviceData);
        model.addAttribute("productData",    productData);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("defaultDateTime",
                LocalDateTime.now().withSecond(0).withNano(0)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
    }
}
