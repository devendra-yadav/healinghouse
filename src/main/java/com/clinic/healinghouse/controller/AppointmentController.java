package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.dto.CalendarActionResponseDTO;
import com.clinic.healinghouse.dto.CalendarEventDTO;
import com.clinic.healinghouse.dto.RescheduleRequestDTO;
import com.clinic.healinghouse.dto.RescheduleResponseDTO;
import com.clinic.healinghouse.dto.TherapistConflictDTO;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.service.*;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
    private final WalletService      walletService;
    private final TreatmentService   treatmentService;
    private final ProductService     productService;
    private final ComboService       comboService;

    // ── List ──────────────────────────────────────────────────────────────
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) Long therapistId,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                       @RequestParam(required = false) String patientName,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        AppointmentStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = AppointmentStatus.valueOf(status.trim());
            } catch (IllegalArgumentException ignored) {}
        }

        int pageSize = PaginationUtil.clampPageSize(size);
        page = PaginationUtil.clampPage(page);
        var appointments = appointmentService.findByFilters(statusEnum, therapistId, dateFrom, dateTo, patientName,
                null, PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "appointmentDateTime")));

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
    public String newForm(@RequestParam(required = false) Long therapistId,
                          @RequestParam(required = false)
                          @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime appointmentDateTime,
                          Model model) {
        populateFormModel(model);
        AppointmentForm form = new AppointmentForm();
        if (therapistId != null) form.setTherapistId(therapistId);
        if (appointmentDateTime != null) form.setAppointmentDateTime(appointmentDateTime);
        model.addAttribute("form", form);
        model.addAttribute("editMode",   false);
        model.addAttribute("formAction", "/appointments/save");
        model.addAttribute("cancelUrl",  "/appointments");
        model.addAttribute("pageTitle",  "New Appointment");
        return "appointments/form";
    }

    // ── Calendar feed (JSON, consumed by therapists/calendar.html) ───────────
    @GetMapping("/calendar-feed")
    @ResponseBody
    public List<CalendarEventDTO> calendarFeed(@RequestParam Long therapistId,
                                               @RequestParam String start,
                                               @RequestParam String end) {
        return appointmentService.findCalendarEvents(therapistId, parseCalendarBound(start), parseCalendarBound(end));
    }

    /** FullCalendar sends range bounds as ISO-8601, with or without an offset, or as a plain date. */
    private LocalDateTime parseCalendarBound(String raw) {
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(raw).atStartOfDay();
            }
        }
    }

    // ── Reschedule (drag/resize on the therapist calendar) ──────────────────
    @PostMapping("/{id}/reschedule")
    @ResponseBody
    public RescheduleResponseDTO reschedule(@PathVariable Long id, @RequestBody RescheduleRequestDTO req) {
        try {
            return appointmentService.rescheduleAppointment(
                    id, req.appointmentDateTime(), req.durationMinutes(), req.forceSave());
        } catch (Exception e) {
            String msg = e.getMessage();
            return new RescheduleResponseDTO(false, msg == null || msg.isBlank() ? "Reschedule failed." : msg, List.of());
        }
    }

    // ── Cancel from the therapist calendar (JSON, no page navigation) ────────
    @PostMapping("/{id}/cancel-from-calendar")
    @ResponseBody
    public CalendarActionResponseDTO cancelFromCalendar(@PathVariable Long id) {
        try {
            appointmentService.cancelAppointment(id, "Cancelled via calendar");
            return new CalendarActionResponseDTO(true, "Appointment cancelled.");
        } catch (Exception e) {
            String msg = e.getMessage();
            return new CalendarActionResponseDTO(false, msg == null || msg.isBlank() ? "Cancel failed." : msg);
        }
    }

    // ── Save (create) ─────────────────────────────────────────────────────
    @PostMapping("/save")
    public String save(@ModelAttribute("form") AppointmentForm form,
                       @RequestParam(defaultValue = "false") boolean forceSave,
                       Model model,
                       RedirectAttributes ra) {
        if (!forceSave) {
            List<TherapistConflictDTO> conflicts = appointmentService.findConflicts(form, null);
            if (!conflicts.isEmpty()) {
                model.addAttribute("conflicts", conflicts);
                return renderAppointmentFormWithError(model, form, null, false, null, null);
            }
        }
        try {
            Appointment saved = appointmentService.createAppointment(form);
            ra.addFlashAttribute("successMessage",
                    "Appointment #" + saved.getId() + " created successfully.");
            return "redirect:/appointments/" + saved.getId();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to save appointment. Please check your input.";
            return renderAppointmentFormWithError(model, form, msg, false, null, null);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to save appointment. Please try again.";
            ra.addFlashAttribute("errorMessage", msg);
            return "redirect:/appointments/new";
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String returnUrl,
                         Model model, RedirectAttributes ra) {
        try {
            Appointment appt = appointmentService.getById(id);
            model.addAttribute("appointment", appt);
            model.addAttribute("therapists", therapistService.findAll());
            model.addAttribute("walletBalance", walletService.getBalance(appt.getPatient().getId()));
            model.addAttribute("returnUrl", returnUrl);
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
    public String editForm(@PathVariable Long id,
                           @RequestParam(required = false) String returnUrl,
                           Model model, RedirectAttributes ra) {
        try {
            Appointment appt = appointmentService.getById(id);
            populateFormModel(model);

            List<Map<String, Object>> existingServiceLines = appt.getServiceLines().stream()
                    .map(sl -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("serviceId",   sl.getService().getId());
                        m.put("quantity",    sl.getQuantity());
                        m.put("therapistId", sl.getTherapist().getId());
                        m.put("comboGroupKey", sl.getAppointmentCombo() != null ? "combo-" + sl.getAppointmentCombo().getId() : null);
                        return m;
                    }).toList();
            List<Map<String, Object>> existingProductLines = appt.getProductLines().stream()
                    .map(pl -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("productId",   pl.getProduct().getId());
                        m.put("quantity",    pl.getQuantity());
                        m.put("therapistId", pl.getTherapist().getId());
                        m.put("comboGroupKey", pl.getAppointmentCombo() != null ? "combo-" + pl.getAppointmentCombo().getId() : null);
                        return m;
                    }).toList();

            model.addAttribute("appointment",          appt);
            model.addAttribute("form",                 AppointmentForm.from(appt));
            model.addAttribute("existingServiceLines", existingServiceLines);
            model.addAttribute("existingProductLines", existingProductLines);
            model.addAttribute("existingComboGroups",  existingComboGroupsFromAppointment(appt));
            model.addAttribute("walletBalance", walletService.getBalance(appt.getPatient().getId()));
            model.addAttribute("editMode",   true);
            model.addAttribute("formAction", "/appointments/" + id + "/update");
            model.addAttribute("returnUrl",  returnUrl);
            model.addAttribute("cancelUrl",  (returnUrl != null && !returnUrl.isBlank()) ? returnUrl : "/appointments/" + id);
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
                         @RequestParam(required = false) String returnUrl,
                         @RequestParam(defaultValue = "false") boolean forceSave,
                         Model model,
                         RedirectAttributes ra) {
        String suffix = (returnUrl != null && !returnUrl.isBlank())
                ? "?returnUrl=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (!forceSave) {
            List<TherapistConflictDTO> conflicts = appointmentService.findConflicts(form, id);
            if (!conflicts.isEmpty()) {
                model.addAttribute("conflicts", conflicts);
                return renderAppointmentFormWithError(model, form, null, true, id, returnUrl);
            }
        }
        try {
            appointmentService.updateAppointment(id, form);
            ra.addFlashAttribute("successMessage", "Appointment #" + id + " updated successfully.");
            return "redirect:/appointments/" + id + suffix;
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to update appointment. Please check your input.";
            return renderAppointmentFormWithError(model, form, msg, true, id, returnUrl);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Failed to update appointment. Please try again.";
            ra.addFlashAttribute("errorMessage", msg);
            return "redirect:/appointments/" + id + "/edit" + suffix;
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

    // ── Per-line therapist reassignment (allowed on any status) ──────────────
    @PostMapping("/{id}/service-lines/{lineId}/reassign-therapist")
    public String reassignServiceLineTherapist(@PathVariable Long id,
                                               @PathVariable Long lineId,
                                               @RequestParam Long therapistId,
                                               @RequestParam(required = false) String returnUrl,
                                               RedirectAttributes ra) {
        try {
            appointmentService.reassignServiceLineTherapist(id, lineId, therapistId);
            ra.addFlashAttribute("successMessage", "Therapist reassigned for that service line.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/appointments/" + id + returnUrlSuffix(returnUrl);
    }

    @PostMapping("/{id}/product-lines/{lineId}/reassign-therapist")
    public String reassignProductLineTherapist(@PathVariable Long id,
                                               @PathVariable Long lineId,
                                               @RequestParam Long therapistId,
                                               @RequestParam(required = false) String returnUrl,
                                               RedirectAttributes ra) {
        try {
            appointmentService.reassignProductLineTherapist(id, lineId, therapistId);
            ra.addFlashAttribute("successMessage", "Therapist reassigned for that product line.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/appointments/" + id + returnUrlSuffix(returnUrl);
    }

    private String returnUrlSuffix(String returnUrl) {
        return (returnUrl != null && !returnUrl.isBlank())
                ? "?returnUrl=" + java.net.URLEncoder.encode(returnUrl, java.nio.charset.StandardCharsets.UTF_8)
                : "";
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Re-renders the appointment form with the staff-submitted data intact, used both for a
     * double-booking conflict warning (errorMessage == null, "conflicts" already on the model)
     * and for a validation-style failure from create/updateAppointment (errorMessage set).
     * Without this, a rejected save/update used to redirect to a blank form, discarding everything
     * the user had entered.
     */
    private String renderAppointmentFormWithError(Model model, AppointmentForm form, String errorMessage,
                                                  boolean editMode, Long id, String returnUrl) {
        populateFormModel(model);
        model.addAttribute("form",                 form);
        if (errorMessage != null) model.addAttribute("errorMessage", errorMessage);
        model.addAttribute("existingServiceLines",  form.getServiceLines());
        model.addAttribute("existingProductLines",  form.getProductLines());
        model.addAttribute("existingComboGroups",   existingComboGroupsFromForm(form));
        model.addAttribute("editMode",   editMode);
        model.addAttribute("formAction", editMode ? "/appointments/" + id + "/update" : "/appointments/save");
        model.addAttribute("cancelUrl",  editMode
                ? ((returnUrl != null && !returnUrl.isBlank()) ? returnUrl : "/appointments/" + id)
                : "/appointments");
        model.addAttribute("pageTitle",  editMode ? "Edit Appointment #" + id : "New Appointment");
        if (editMode) {
            model.addAttribute("returnUrl", returnUrl);
            try {
                Appointment appt = appointmentService.getById(id);
                model.addAttribute("appointment", appt);
                model.addAttribute("walletBalance", walletService.getBalance(appt.getPatient().getId()));
            } catch (Exception ignored) {
                // Appointment may have been removed concurrently; the form still renders without the sidebar.
            }
        }
        return "appointments/form";
    }

    /** Combo group metadata for edit-mode pre-population, keyed the same way AppointmentForm.from does. */
    private List<Map<String, Object>> existingComboGroupsFromAppointment(Appointment appt) {
        return appt.getCombos().stream()
                .map(ac -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("groupKey", "combo-" + ac.getId());
                    m.put("comboId", ac.getCombo().getId());
                    m.put("comboName", ac.getComboNameSnapshot());
                    m.put("discountType", ac.getDiscountType() != null ? ac.getDiscountType().name() : "NONE");
                    m.put("discountValue", ac.getDiscountValue() != null ? ac.getDiscountValue() : java.math.BigDecimal.ZERO);
                    return m;
                }).toList();
    }

    /** Same shape as above, but re-rendering after a conflict warning — combo names looked up live since the submitted form only carries comboId/groupKey. */
    private List<Map<String, Object>> existingComboGroupsFromForm(AppointmentForm form) {
        return form.getComboSelections().stream()
                .filter(sel -> sel != null && sel.getComboId() != null && sel.getGroupKey() != null && !sel.getGroupKey().isBlank())
                .map(sel -> {
                    Combo combo = comboService.getById(sel.getComboId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("groupKey", sel.getGroupKey());
                    m.put("comboId", combo.getId());
                    m.put("comboName", combo.getName());
                    m.put("discountType", combo.getDiscountType() != null ? combo.getDiscountType().name() : "NONE");
                    m.put("discountValue", combo.getDiscountValue() != null ? combo.getDiscountValue() : java.math.BigDecimal.ZERO);
                    return m;
                }).toList();
    }

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

        List<Map<String, Object>> therapistData = therapistService.findAll().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",   t.getId());
                    m.put("name", t.getFullName());
                    return m;
                }).toList();

        model.addAttribute("patients",       patientService.findAll());
        model.addAttribute("therapists",     therapistService.findAll());
        model.addAttribute("serviceData",    serviceData);
        model.addAttribute("productData",    productData);
        model.addAttribute("therapistData",  therapistData);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("discountTypes",  DiscountType.values());
        model.addAttribute("defaultDateTime",
                LocalDateTime.now().withSecond(0).withNano(0)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
    }
}
