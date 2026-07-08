package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private static final int TREND_DAYS = 7;
    private static final int TAG_BREAKDOWN_DAYS = 30;

    private final DashboardService dashboardService;

    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now();

        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("kpis", dashboardService.getTodayKPIs());
        model.addAttribute("todayAppointments", dashboardService.getTodayAppointments());
        model.addAttribute("lowStockAlerts", dashboardService.getLowStockAlerts());
        model.addAttribute("revenueTrend", dashboardService.getRevenueTrend(TREND_DAYS));
        model.addAttribute("tagRevenueBreakdown",
                dashboardService.getTagRevenueBreakdown(today.minusDays(TAG_BREAKDOWN_DAYS - 1), today));

        return "dashboard";
    }
}
