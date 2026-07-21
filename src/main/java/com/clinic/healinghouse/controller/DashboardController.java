package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final HealingHouseProperties properties;

    @RequiresPermission(module = Module.DASHBOARD, action = PermissionAction.VIEW)
    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now();
        int trendDays = properties.getDashboard().getTrendDays();
        int tagBreakdownDays = properties.getDashboard().getTagBreakdownDays();

        model.addAttribute("pageTitle", "Home");
        model.addAttribute("kpis", dashboardService.getTodayKPIs());
        model.addAttribute("todayAppointments", dashboardService.getTodayAppointments());
        model.addAttribute("lowStockAlerts", dashboardService.getLowStockAlerts());
        model.addAttribute("revenueTrend", dashboardService.getRevenueTrend(trendDays));
        model.addAttribute("tagRevenueBreakdown",
                dashboardService.getTagRevenueBreakdown(today.minusDays(tagBreakdownDays - 1), today));

        return "dashboard";
    }

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("pageTitle", "Help");
        return "help";
    }
}
