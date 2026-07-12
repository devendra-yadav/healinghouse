package com.clinic.healinghouse.dto;

/** Generic success/message response for calendar AJAX actions (e.g. cancel-from-calendar). */
public record CalendarActionResponseDTO(
        boolean success,
        String message
) {
}
