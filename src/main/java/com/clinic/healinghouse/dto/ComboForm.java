package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Combo;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Combo has two nested indexed item lists with quantities, so — unlike ClinicService/Product,
 * which bind directly to the entity — it needs a dedicated Form DTO (same nested-list pattern
 * as AppointmentForm's ServiceLineForm/ProductLineForm).
 */
@Data
public class ComboForm {

    private Long id;
    private String name;
    private String description;
    private boolean active = true;
    private String discountType;   // "NONE" | "PERCENTAGE" | "FLAT"
    private BigDecimal discountValue;
    private List<ComboItemForm> serviceItems = new ArrayList<>();
    private List<ComboItemForm> productItems = new ArrayList<>();

    public static ComboForm from(Combo combo) {
        ComboForm form = new ComboForm();
        form.setId(combo.getId());
        form.setName(combo.getName());
        form.setDescription(combo.getDescription());
        form.setActive(combo.isActive());
        form.setDiscountType(combo.getDiscountType() != null ? combo.getDiscountType().name() : "NONE");
        form.setDiscountValue(combo.getDiscountValue());
        combo.getServiceItems().forEach(si -> {
            ComboItemForm item = new ComboItemForm();
            item.setItemId(si.getService().getId());
            item.setQuantity(si.getQuantity());
            form.getServiceItems().add(item);
        });
        combo.getProductItems().forEach(pi -> {
            ComboItemForm item = new ComboItemForm();
            item.setItemId(pi.getProduct().getId());
            item.setQuantity(pi.getQuantity());
            form.getProductItems().add(item);
        });
        return form;
    }

    @Data
    public static class ComboItemForm {
        private Long itemId;
        private int quantity = 1;
    }
}
