package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Therapist;

import java.math.BigDecimal;

/**
 * Per-therapist earnings breakdown for a date range — the building block for every
 * Phase 3 report (daily, period, comparison). Commission is attributed per-line
 * (services/products actually performed by this therapist), not by appointment.
 */
public record TherapistEarningsDTO(
        Therapist therapist,
        BigDecimal servicesRevenue,
        BigDecimal productsRevenue,
        long servicesCount,
        BigDecimal allServicesRevenue,
        BigDecimal allProductsRevenue,
        long allServicesCount,
        BigDecimal bonusTaggedServicesRevenue,
        BigDecimal serviceCommission,
        BigDecimal productCommission,
        BigDecimal totalCommission,
        boolean bonusEarned,
        BigDecimal bonusAmount,
        BigDecimal totalVariablePay,
        BigDecimal fixedMonthlySalary
) {
    public BigDecimal totalRevenue() {
        return servicesRevenue.add(productsRevenue);
    }
}
