package com.clinic.healinghouse.dto;

import java.util.List;

/** Result of a calendar drag/resize: either saved, or blocked by conflicts pending a forced retry. */
public record RescheduleResponseDTO(
        boolean success,
        String message,
        List<TherapistConflictDTO> conflicts
) {
}
