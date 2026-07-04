package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final AppointmentService appointmentService;

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Appointment> todayAppointments = appointmentService.findTodayAppointments();
        model.addAttribute("todayAppointments", todayAppointments);
        model.addAttribute("pageTitle", "Dashboard");
        return "dashboard";
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("pageTitle", "Reports");
        return "reports/index";
    }
}