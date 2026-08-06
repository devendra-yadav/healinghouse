package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Row projection backing the Package Templates list page's CSV/PDF export — unlike
 *  {@link PackageTemplateSuggestionDTO} (which only ever serves the sell-package modal's template
 *  picker), this carries {@code description} and {@code active} since an export can include
 *  deactivated templates. */
public record PackageTemplateExportRowDTO(String name, String description, String itemsSummary,
                                           BigDecimal suggestedPrice, boolean active) {}
