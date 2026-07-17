package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.CalendarTherapistOptionDTO;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.util.TherapistColorUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** All-therapists overlay calendar (GET /calendar) — see All_Therapists_Calendar_Requirements_v1.md. */
@Controller
@RequiredArgsConstructor
public class CalendarController {

    private final TherapistService therapistService;
    private final PermissionService permissionService;

    @RequiresPermission(module = Module.APPOINTMENTS, action = PermissionAction.VIEW)
    @GetMapping("/calendar")
    public String calendar(Model model) {
        // A THERAPIST is scoped to "own only" appointments — an overlay of every therapist's
        // schedule is out of scope for that role regardless of the coarse APPOINTMENTS,VIEW grant
        // needed for their own calendar elsewhere (requirements/Security_RBAC_Requirements_v1.md §7).
        if (permissionService.currentTherapistId() != null) {
            throw new AccessDeniedException("The all-therapists calendar isn't available for your role.");
        }

        var therapistOptions = therapistService.findAll().stream()
                .map(t -> new CalendarTherapistOptionDTO(t.getId(), t.getFullName(), TherapistColorUtil.colorFor(t.getId())))
                .toList();

        model.addAttribute("therapistOptions", therapistOptions);
        model.addAttribute("pageTitle", "All-Therapists Calendar");
        return "calendar";
    }
}
