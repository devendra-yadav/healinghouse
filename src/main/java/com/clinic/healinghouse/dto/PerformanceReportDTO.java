package com.clinic.healinghouse.dto;

import java.time.LocalDate;
import java.util.List;

/** Service, product, and tag-level performance breakdown for a date range. */
public record PerformanceReportDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<ServicePerformanceDTO> services,
        List<ProductPerformanceDTO> products,
        List<TagRevenueDTO> tagRevenue,
        List<TagTherapistRevenueDTO> tagTherapistRevenue
) {
}
