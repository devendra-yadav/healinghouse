package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Lightweight projection backing the combo picker's search dropdown on the appointment form. */
public record ComboSuggestionDTO(Long id, String name, String itemsSummary,
                                  BigDecimal originalPrice, BigDecimal comboPrice, BigDecimal savings) {}
