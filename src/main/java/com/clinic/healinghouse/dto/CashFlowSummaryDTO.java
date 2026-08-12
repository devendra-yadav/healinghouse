package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/**
 * Real money movement only — see CashFlowReportAggregator for the exact sources of each figure and
 * why wallet/package USAGE-REVERSAL never appear here (internal transfers, not money movement).
 */
public record CashFlowSummaryDTO(
        BigDecimal appointmentCashInflow,
        BigDecimal packageSales,
        BigDecimal walletTopUps,
        BigDecimal totalInflow,
        BigDecimal expenses,
        BigDecimal walletRefunds,
        BigDecimal packageRefunds,
        BigDecimal appointmentPaymentCorrections,
        BigDecimal totalOutflow,
        BigDecimal netCashFlow
) {
}
