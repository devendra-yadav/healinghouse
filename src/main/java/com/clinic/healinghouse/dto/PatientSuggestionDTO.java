package com.clinic.healinghouse.dto;

/** Lightweight projection backing the name/phone autocomplete dropdown on the patients list search. */
public record PatientSuggestionDTO(Long id, String fullName, String phone) {
}
