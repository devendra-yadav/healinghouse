package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Lightweight projection backing the templates list page and the sell-package modal's template picker. */
public record PackageTemplateSuggestionDTO(Long id, String name, String itemsSummary, BigDecimal suggestedPrice) {}
