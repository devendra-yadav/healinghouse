package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One drill-down row on the Actual Revenue report's appointment table. */
public record AppointmentRevenueRowDTO(
        Long appointmentId,
        LocalDateTime dateTime,
        String patientName,
        String therapistName,
        AppointmentStatus status,
        BigDecimal gross,
        BigDecimal discount,
        BigDecimal net,
        BigDecimal collected,
        BigDecimal outstanding,
        PaymentMethod paymentMethod
) {
}
