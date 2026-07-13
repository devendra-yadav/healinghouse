package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Headline cards for the Actual Revenue report — the real, post-discount billed/collected figures. */
public record RevenueSummaryDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        long appointmentCount,
        BigDecimal grossRevenue,
        BigDecimal comboDiscount,
        BigDecimal manualDiscount,
        BigDecimal netRevenue,
        BigDecimal collected,
        BigDecimal outstanding,
        BigDecimal walletFunded,
        BigDecimal advanceReceived
) {
}
