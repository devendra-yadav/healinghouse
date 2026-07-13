package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Full combo contents for expansion into appointment-form line rows (GET /combos/{id}/detail). */
public record ComboDetailDTO(Long id, String name, String discountType, BigDecimal discountValue,
                              List<ComboDetailItemDTO> serviceItems, List<ComboDetailItemDTO> productItems) {

    public record ComboDetailItemDTO(Long itemId, int quantity) {}
}
