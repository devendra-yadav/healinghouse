package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.CalendarTherapistOptionDTO;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.util.TherapistColorUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** All-therapists overlay calendar (GET /calendar) — see All_Therapists_Calendar_Requirements_v1.md. */
@Controller
@RequiredArgsConstructor
public class CalendarController {

    private final TherapistService therapistService;

    @GetMapping("/calendar")
    public String calendar(Model model) {
        var therapistOptions = therapistService.findAll().stream()
                .map(t -> new CalendarTherapistOptionDTO(t.getId(), t.getFullName(), TherapistColorUtil.colorFor(t.getId())))
                .toList();

        model.addAttribute("therapistOptions", therapistOptions);
        model.addAttribute("pageTitle", "All-Therapists Calendar");
        return "calendar";
    }
}
