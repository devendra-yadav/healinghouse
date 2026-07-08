package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Clinic-wide totals for a date range (completed appointments only) — shared by all report types. */
public record PeriodSummaryDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        long totalAppointments,
        long uniquePatients,
        BigDecimal totalServicesRevenue,
        BigDecimal totalProductsRevenue
) {
    public BigDecimal totalRevenue() {
        return totalServicesRevenue.add(totalProductsRevenue);
    }
}
