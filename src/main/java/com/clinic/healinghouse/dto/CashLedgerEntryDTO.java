package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row in the Cash Flow report's combined, date-sorted ledger — amount is always positive; direction ("IN"/"OUT") carries the sign. */
public record CashLedgerEntryDTO(
        LocalDateTime date,
        String source,
        String direction,
        String description,
        BigDecimal amount,
        PaymentMethod paymentMethod
) {
}
