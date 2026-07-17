package com.clinic.healinghouse.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                         @RequestParam(required = false) String logout,
                         Model model) {
        model.addAttribute("pageTitle", "Login");
        if (error != null) {
            model.addAttribute("errorMessage", "Invalid username or password, or the account is locked/disabled.");
        }
        if (logout != null) {
            model.addAttribute("successMessage", "You have been logged out.");
        }
        return "auth/login";
    }
}