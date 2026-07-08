package com.clinic.healinghouse.dto;

import java.time.LocalDateTime;

/** Internal projection: a patient's earliest-ever appointment date, for new-vs-repeat classification. */
public record PatientFirstVisitDTO(
        Long patientId,
        LocalDateTime firstVisitDateTime
) {
}
