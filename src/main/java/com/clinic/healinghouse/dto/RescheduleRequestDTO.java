package com.clinic.healinghouse.dto;

import java.time.LocalDateTime;

/** Drag/resize request from the therapist calendar (JSON body of POST /appointments/{id}/reschedule). */
public record RescheduleRequestDTO(
        LocalDateTime appointmentDateTime,
        Integer durationMinutes,
        boolean forceSave
) {
}
