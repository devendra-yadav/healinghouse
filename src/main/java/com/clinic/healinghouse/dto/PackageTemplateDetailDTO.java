package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Plain-data projection of a PackageTemplate's contents, for the sell-package modal's template
 * picker JS — a dedicated DTO (rather than serializing the entity via th:inline javascript)
 * avoids the parent/child circular reference between PackageTemplate and its item entities that
 * a direct Jackson serialization of the JPA graph would hit.
 */
public record PackageTemplateDetailDTO(Long id, String name, BigDecimal suggestedPrice,
                                        List<ItemLine> serviceItems, List<ItemLine> productItems) {

    public record ItemLine(Long itemId, int sessionCount) {}
}
