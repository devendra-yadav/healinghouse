package com.clinic.healinghouse.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;

/** Full payload for the Actual Revenue report (/reports/revenue): real, post-discount billed/collected figures with filters. */
public record RevenueReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        RevenueSummaryDTO summary,
        List<RevenueByPaymentMethodDTO> byPaymentMethod,
        List<RevenueByTherapistDTO> byTherapist,
        List<RevenueByCatalogItemDTO> servicesNetRevenue,
        List<RevenueByCatalogItemDTO> productsNetRevenue,
        RevenueTrendDTO trend,
        Page<AppointmentRevenueRowDTO> appointments
) {
}
