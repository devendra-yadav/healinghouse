package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.CalendarTherapistOptionDTO;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.util.TherapistColorUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** All-therapists overlay calendar (GET /calendar) — see All_Therapists_Calendar_Requirements_v1.md.
 *  Every authenticated user with APPOINTMENTS,VIEW (including THERAPIST) can see every therapist's
 *  schedule here — this is read-only, so the per-role "own data only" scoping that applies to the
 *  appointments list/reports doesn't extend to this view. */
@Controller
@RequiredArgsConstructor
public class CalendarController {

    private final TherapistService therapistService;

    @RequiresPermission(module = Module.APPOINTMENTS, action = PermissionAction.VIEW)
    @GetMapping("/calendar")
    public String calendar(Model model) {
        var therapistOptions = therapistService.findAll().stream()
                .map(t -> new CalendarTherapistOptionDTO(t.getId(), t.getFullName(), TherapistColorUtil.colorFor(t.getId())))
                .toList();

        model.addAttribute("therapistOptions", therapistOptions);
        model.addAttribute("pageTitle", "Clinic Calendar");
        return "calendar";
    }
}
