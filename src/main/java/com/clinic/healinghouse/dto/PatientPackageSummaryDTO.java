package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.PatientPackageStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Feeds the patient detail page's Packages card — one row per PatientPackage. */
public record PatientPackageSummaryDTO(Long id, String name, PatientPackageStatus status,
                                        BigDecimal totalPrice, LocalDate expiryDate, LocalDateTime purchasedAt,
                                        BigDecimal refundableValue, List<ItemLine> items) {

    public record ItemLine(String name, int sessionsTotal, int sessionsUsed, int sessionsRemaining) {}
}
