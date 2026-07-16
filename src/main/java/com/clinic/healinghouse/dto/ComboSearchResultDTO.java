package com.clinic.healinghouse.dto;

import java.util.List;

/** Paginated response for the combo picker's browse/search endpoint ({@code GET /combos/search}). */
public record ComboSearchResultDTO(List<ComboSuggestionDTO> combos, int page, int totalPages,
                                    boolean hasPrevious, boolean hasNext) {}
