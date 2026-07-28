package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

/** Row projection backing the Combos list page's CSV/PDF export — unlike {@link ComboSuggestionDTO}
 *  (which only ever serves active/selectable combos to the appointment-form picker), this carries
 *  {@code description} and {@code active} since an export can include deactivated combos. */
public record ComboExportRowDTO(String name, String description, String itemsSummary,
                                 BigDecimal originalPrice, BigDecimal comboPrice, BigDecimal savings,
                                 boolean active) {}
