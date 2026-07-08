package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Today's KPI numbers for the dashboard cards row. */
public record DashboardKpiDTO(
        long todayAppointmentsCount,
        BigDecimal todayRevenue,
        long lowStockCount,
        long activeTherapistsCount
) {
}
