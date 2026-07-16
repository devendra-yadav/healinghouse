package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.PackageTemplate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Nested indexed item-list DTO, same pattern as ComboForm. */
@Data
public class PackageTemplateForm {

    private Long id;
    private String name;
    private String description;
    private boolean active = true;
    private String discountType;   // "NONE" | "PERCENTAGE" | "FLAT"
    private BigDecimal discountValue;
    private List<PackageTemplateItemForm> serviceItems = new ArrayList<>();
    private List<PackageTemplateItemForm> productItems = new ArrayList<>();

    public static PackageTemplateForm from(PackageTemplate template) {
        PackageTemplateForm form = new PackageTemplateForm();
        form.setId(template.getId());
        form.setName(template.getName());
        form.setDescription(template.getDescription());
        form.setActive(template.isActive());
        form.setDiscountType(template.getDiscountType() != null ? template.getDiscountType().name() : "NONE");
        form.setDiscountValue(template.getDiscountValue());
        template.getServiceItems().forEach(si -> {
            PackageTemplateItemForm item = new PackageTemplateItemForm();
            item.setItemId(si.getService().getId());
            item.setSessionCount(si.getSessionCount());
            form.getServiceItems().add(item);
        });
        template.getProductItems().forEach(pi -> {
            PackageTemplateItemForm item = new PackageTemplateItemForm();
            item.setItemId(pi.getProduct().getId());
            item.setSessionCount(pi.getSessionCount());
            form.getProductItems().add(item);
        });
        return form;
    }

    @Data
    public static class PackageTemplateItemForm {
        private Long itemId;
        private int sessionCount = 1;
    }
}
