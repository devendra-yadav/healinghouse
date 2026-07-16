package com.clinic.healinghouse.dto;

/** One row in the all-therapists calendar's checkbox panel — id/name plus its stable palette color. */
public record CalendarTherapistOptionDTO(
        Long id,
        String fullName,
        String color
) {
}
