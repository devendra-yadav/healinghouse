package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.PaymentMethod;

import java.time.LocalDate;

/** Filter inputs for the Actual Revenue report (/reports/revenue). All fields optional except the date range. */
public record RevenueReportFilter(
        LocalDate dateFrom,
        LocalDate dateTo,
        Long therapistId,
        String patientName,
        Long serviceId,
        Long productId,
        String tagName,
        PaymentMethod paymentMethod,
        AppointmentStatus status,
        boolean discountedOnly
) {
}
