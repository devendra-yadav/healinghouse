package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** One appointment rendered as a FullCalendar event on a therapist's calendar page. */
public record CalendarEventDTO(
        Long id,
        String title,
        String start,
        String end,
        String color,
        String status,
        Long therapistId,
        BigDecimal amountPaid
) {
}
