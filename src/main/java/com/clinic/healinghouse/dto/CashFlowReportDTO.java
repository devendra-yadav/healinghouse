package com.clinic.healinghouse.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;

public record CashFlowReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        CashFlowSummaryDTO summary,
        List<CashFlowByPaymentMethodDTO> inflowByPaymentMethod,
        List<CashFlowTrendPointDTO> trend,
        Page<CashLedgerEntryDTO> ledger
) {
}
