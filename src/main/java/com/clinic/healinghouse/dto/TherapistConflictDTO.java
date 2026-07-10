package com.clinic.healinghouse.dto;

import java.time.LocalDateTime;

/** A single therapist double-booking warning surfaced on the appointment form before save. */
public record TherapistConflictDTO(
        Long therapistId,
        String therapistName,
        Long conflictingAppointmentId,
        String patientName,
        LocalDateTime conflictStart,
        LocalDateTime conflictEnd
) {
}
