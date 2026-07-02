package com.clinic.healinghouse.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("pageTitle", "Dashboard");
        return "dashboard";
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("pageTitle", "Reports");
        return "reports/index";
    }
}