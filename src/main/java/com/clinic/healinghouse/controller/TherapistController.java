package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AppointmentService;
import com.clinic.healinghouse.service.CommissionCalculator;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.util.PaginationUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/therapists")
@RequiredArgsConstructor
public class TherapistController {

    private final TherapistService therapistService;
    private final AppointmentService appointmentService;
    private final CommissionCalculator commissionCalculator;
    private final PaginationUtil paginationUtil;
    private final PermissionService permissionService;

    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        // THERAPIST role: matrix scopes THERAPISTS to "own profile only" — no full roster.
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId != null) {
            return "redirect:/therapists/" + ownTherapistId;
        }

        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        model.addAttribute("therapists", showInactive
                ? therapistService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("fullName")))
                : therapistService.findAll(PageRequest.of(page, pageSize)));
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Therapists");
        return "therapists/list";
    }

    // ── Detail (profile + period earnings summary + filterable appointment history) ──
    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.VIEW)
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String patientName,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                         @RequestParam(defaultValue = "0") int page,
                         Model model, RedirectAttributes ra) {
        enforceOwnTherapist(id);
        try {
            Therapist therapist = therapistService.getById(id);
            page = paginationUtil.clampPage(page);

            LocalDate effectiveDateFrom = dateFrom != null ? dateFrom : LocalDate.now().withDayOfMonth(1);
            LocalDate effectiveDateTo;
            if (dateTo != null) {
                effectiveDateTo = dateTo;
            } else {
                // Default upper bound is "today", widened to the therapist's furthest-out
                // appointment (any status) if one exists beyond today — otherwise an appointment
                // dragged forward on the calendar (or dragged forward then cancelled) would
                // silently vanish from this default view.
                LocalDate today = LocalDate.now();
                LocalDate latestAppointment = appointmentService.findLatestAppointmentDate(id).orElse(today);
                effectiveDateTo = latestAppointment.isAfter(today) ? latestAppointment : today;
            }

            AppointmentStatus statusEnum = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusEnum = AppointmentStatus.valueOf(status.trim());
                } catch (IllegalArgumentException ignored) {}
            }

            var appointments = appointmentService.findByFilters(
                    statusEnum, id, effectiveDateFrom, effectiveDateTo, patientName, null,
                    PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "appointmentDateTime")));
            long completedCount = appointmentService.countCompleted(
                    statusEnum, id, effectiveDateFrom, effectiveDateTo, patientName, null);

            model.addAttribute("therapist", therapist);
            model.addAttribute("earnings",
                    commissionCalculator.calculateEarnings(therapist, effectiveDateFrom, effectiveDateTo));
            model.addAttribute("appointments", appointments);
            model.addAttribute("totalAppointments", appointments.getTotalElements());
            model.addAttribute("completedCount", completedCount);
            model.addAttribute("statuses",           AppointmentStatus.values());
            model.addAttribute("selectedStatus",      status);
            model.addAttribute("selectedPatientName", patientName);
            model.addAttribute("selectedDateFrom",    effectiveDateFrom);
            model.addAttribute("selectedDateTo",      effectiveDateTo);
            model.addAttribute("pageTitle", "Therapist — " + therapist.getFullName());
            return "therapists/detail";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load therapist: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/therapists";
        }
    }

    // ── Calendar (read-only day/week/month schedule view) ─────────────────
    @RequiresPermission(module = Module.APPOINTMENTS, action = PermissionAction.VIEW)
    @GetMapping("/{id}/calendar")
    public String calendar(@PathVariable Long id, Model model, RedirectAttributes ra) {
        enforceOwnTherapist(id);
        try {
            Therapist therapist = therapistService.getById(id);
            model.addAttribute("therapist",  therapist);
            model.addAttribute("therapists", therapistService.findAll());
            model.addAttribute("pageTitle",  "Calendar — " + therapist.getFullName());
            return "therapists/calendar";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load therapist: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/therapists";
        }
    }

    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("therapist", Therapist.builder().build());
        model.addAttribute("pageTitle", "New Therapist");
        return "therapists/form";
    }

    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("therapist", therapistService.getById(id));
        model.addAttribute("pageTitle", "Edit Therapist");
        return "therapists/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("therapist") Therapist therapist,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        permissionService.require(Module.THERAPISTS, therapist.getId() == null ? PermissionAction.CREATE : PermissionAction.EDIT);
        if (result.hasErrors()) {
            model.addAttribute("pageTitle", therapist.getId() == null ? "New Therapist" : "Edit Therapist");
            return "therapists/form";
        }
        therapistService.save(therapist);
        ra.addFlashAttribute("successMessage", "Therapist saved successfully.");
        return "redirect:/therapists";
    }

    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        therapistService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Therapist deactivated successfully.");
        return "redirect:/therapists";
    }

    @RequiresPermission(module = Module.THERAPISTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        therapistService.activate(id);
        ra.addFlashAttribute("successMessage", "Therapist reactivated successfully.");
        return "redirect:/therapists/" + id;
    }

    /** THERAPIST role is scoped to their own profile only (requirements/Security_RBAC_Requirements_v1.md §4). */
    private void enforceOwnTherapist(Long id) {
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId != null && !ownTherapistId.equals(id)) {
            throw new AccessDeniedException("You can only view your own therapist profile.");
        }
    }
}