package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Full combo contents for expansion into appointment-form line rows (GET /combos/{id}/detail). */
public record ComboDetailDTO(Long id, String name, String discountType, BigDecimal discountValue,
                              List<ComboDetailItemDTO> serviceItems, List<ComboDetailItemDTO> productItems) {

    /**
     * price is the live catalog price at the moment of this request — carried through so the
     * appointment form's combo picker can render each expanded line at the same price this response
     * was just computed with, instead of falling back to the page's stale page-load price snapshot
     * (SERVICE_DATA/PRODUCT_DATA), which can have gone stale if the catalog changed after page load.
     */
    public record ComboDetailItemDTO(Long itemId, int quantity, BigDecimal price) {}
}
