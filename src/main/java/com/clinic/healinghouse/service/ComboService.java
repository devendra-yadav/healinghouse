package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ComboForm;
import com.clinic.healinghouse.dto.ComboSuggestionDTO;
import com.clinic.healinghouse.entity.Combo;
import com.clinic.healinghouse.entity.ComboProductItem;
import com.clinic.healinghouse.entity.ComboServiceItem;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.DiscountType;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.AppointmentComboRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Manages the Combo catalog (bundles of services/products sold at a discounted combo price). */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ComboService {

    private final ComboRepository comboRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final AppointmentComboRepository appointmentComboRepository;

    @Transactional(readOnly = true)
    public List<Combo> findAllActive() {
        return comboRepository.findByActiveTrueOrderByNameAsc();
    }

    /**
     * Paginated variant, used by the combos list page. Unlike the unpaginated {@link #search(String)}
     * (used by the appointment-form combo picker autocomplete, which must stay active-only), a filtered
     * list-page search always matches active AND inactive — staff need to be able to find a deactivated
     * combo by name without paging through the whole inactive list.
     */
    @Transactional(readOnly = true)
    public Page<Combo> search(String query, Pageable pageable) {
        if (StringUtils.hasText(query)) {
            return comboRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim(), pageable);
        }
        return comboRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    /** Includes deactivated combos too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<Combo> findAllIncludingInactive(Pageable pageable) {
        return comboRepository.findAll(pageable);
    }

    /**
     * Unpaginated, for the appointment-form combo picker — a blank query returns every active
     * combo (the picker's initial "browse all" list on open), a non-blank one filters by name
     * (caller caps the filtered result size). Only offers combos that are still fully selectable
     * (see isSelectable) — a combo whose service/product was deactivated after the combo itself
     * was created shouldn't keep being offered live, even though the combo row itself is still "active".
     */
    @Transactional(readOnly = true)
    public List<Combo> search(String query) {
        List<Combo> candidates = StringUtils.hasText(query)
                ? comboRepository.findByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(query.trim())
                : comboRepository.findByActiveTrueOrderByNameAsc();
        return candidates.stream().filter(this::isSelectable).toList();
    }

    /**
     * Paged variant of {@link #search(String)} for the combo picker — the isSelectable filter can't
     * be pushed into the DB query, so this slices the already-filtered in-memory list rather than
     * paginating at the repository level (fine for a clinic's combo catalog size). Named distinctly
     * from the {@link #search(String, Pageable)} list-page overload above, which has different
     * active/inactive semantics.
     */
    @Transactional(readOnly = true)
    public Page<Combo> searchSelectable(String query, Pageable pageable) {
        List<Combo> all = search(query);
        int start = Math.min((int) pageable.getOffset(), all.size());
        int end = Math.min(start + pageable.getPageSize(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(start, end), pageable, all.size());
    }

    /** True if every service/product item the combo bundles is still active — false once any one of them is deactivated. */
    public boolean isSelectable(Combo combo) {
        return combo.getServiceItems().stream().allMatch(si -> si.getService().isActive())
                && combo.getProductItems().stream().allMatch(pi -> pi.getProduct().isActive());
    }

    /**
     * Two separate queries in the same session (avoids MultipleBagFetchException — Combo has
     * two @OneToMany bags, the same shape Appointment already works around in getById).
     */
    @Transactional(readOnly = true)
    public Combo getById(Long id) {
        Combo combo = comboRepository.findWithServiceItemsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Combo not found: " + id));
        comboRepository.findWithProductItemsById(id); // merges productItems into the L1 cache
        return combo;
    }

    public Combo save(ComboForm form) {
        // Combo.name is @NotBlank, so a blank submission would otherwise fail JPA's flush-time
        // validation as a ConstraintViolationException — saveAndRedirect only catches
        // IllegalArgumentException, so that exception escaped to the generic error page with all
        // entered data lost (Bug_Report_v4.md #10). Checked here, before anything is persisted.
        if (!StringUtils.hasText(form.getName())) {
            throw new IllegalArgumentException("Name is required.");
        }
        Combo combo = form.getId() != null ? getById(form.getId()) : Combo.builder().build();
        boolean isNew = combo.getId() == null;

        combo.setName(form.getName());
        combo.setDescription(form.getDescription());
        combo.setActive(form.isActive());

        // Wipe and rebuild both item collections from the form — same pattern AppointmentService
        // uses for rebuilding service/product lines; cascade ALL + orphanRemoval handles deletes.
        combo.getServiceItems().clear();
        for (ComboForm.ComboItemForm item : form.getServiceItems()) {
            if (item == null || item.getItemId() == null) continue;
            ClinicService cs = clinicServiceRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Service not found: " + item.getItemId()));
            if (!cs.isActive()) {
                throw new IllegalArgumentException("Service '" + cs.getName() + "' is inactive and cannot be added to a combo.");
            }
            combo.getServiceItems().add(
                    ComboServiceItem.builder()
                            .combo(combo)
                            .service(cs)
                            .quantity(Math.max(1, item.getQuantity()))
                            .build());
        }

        combo.getProductItems().clear();
        for (ComboForm.ComboItemForm item : form.getProductItems()) {
            if (item == null || item.getItemId() == null) continue;
            Product product = productRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found: " + item.getItemId()));
            if (!product.isActive()) {
                throw new IllegalArgumentException("Product '" + product.getName() + "' is inactive and cannot be added to a combo.");
            }
            combo.getProductItems().add(
                    ComboProductItem.builder()
                            .combo(combo)
                            .product(product)
                            .quantity(Math.max(1, item.getQuantity()))
                            .build());
        }

        if (combo.getServiceItems().isEmpty() && combo.getProductItems().isEmpty()) {
            throw new IllegalArgumentException("A combo must have at least one service or product.");
        }

        DiscountType type = resolveDiscountType(form.getDiscountType());
        if (type != DiscountType.NONE) {
            // A null value here would otherwise be stored as-is and defer the failure from combo-save
            // time to appointment-booking time (computeDiscountAmount silently treats null as "no
            // discount," which isn't the same as the negative-surprise this is meant to catch —
            // Bug_Report_v4.md #11). A negative value would otherwise pass through unrejected and
            // become a de-facto surcharge once applyComboDiscount/distributeDiscount consume it.
            if (form.getDiscountValue() == null) {
                throw new IllegalArgumentException("A discount value is required when a discount type is selected.");
            }
            if (form.getDiscountValue().signum() < 0) {
                throw new IllegalArgumentException("Discount value cannot be negative.");
            }
        }
        if (type == DiscountType.PERCENTAGE && form.getDiscountValue() != null
                && form.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100%.");
        }
        combo.setDiscountType(type);
        combo.setDiscountValue(type == DiscountType.NONE ? null : form.getDiscountValue());

        Combo saved = comboRepository.save(combo);
        log.info("{} combo id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    public void deactivate(Long id) {
        Combo combo = getById(id);
        combo.setActive(false);
        comboRepository.save(combo);
        log.info("Deactivated combo id={} name='{}'", combo.getId(), combo.getName());
    }

    public void activate(Long id) {
        Combo combo = getById(id);
        combo.setActive(true);
        comboRepository.save(combo);
        log.info("Reactivated combo id={} name='{}'", combo.getId(), combo.getName());
    }

    /** Summary handed back to the caller (TreatmentService/ProductService) to build a flash message. */
    public record CatalogItemRemovalResult(int combosAffected, int combosAutoDeactivated) {
        public boolean isEmpty() {
            return combosAffected == 0;
        }

        /** Flash-message suffix, e.g. " Removed from 2 combo(s); 1 auto-deactivated (no items left)." */
        public String describe() {
            if (isEmpty()) return "";
            String msg = " Removed from " + combosAffected + " combo(s)";
            if (combosAutoDeactivated > 0) {
                msg += "; " + combosAutoDeactivated + " auto-deactivated (no items left)";
            }
            return msg + ".";
        }
    }

    /**
     * Called when a ClinicService is deactivated: strips it out of every combo that bundles it
     * (combo price is always live-computed, so it adjusts for free once the item is gone) and
     * auto-deactivates any combo left with zero items — a combo can never have none, per the
     * same rule {@link #save} enforces on the form. Deliberately one-way: reactivating the
     * service later does not re-add it to any combo.
     */
    public CatalogItemRemovalResult handleServiceDeactivated(Long serviceId) {
        List<Combo> combos = comboRepository.findByServiceItems_Service_Id(serviceId);
        return removeFromCombos(combos, combo -> combo.getServiceItems().removeIf(si -> si.getService().getId().equals(serviceId)));
    }

    /** Product equivalent of {@link #handleServiceDeactivated}. */
    public CatalogItemRemovalResult handleProductDeactivated(Long productId) {
        List<Combo> combos = comboRepository.findByProductItems_Product_Id(productId);
        return removeFromCombos(combos, combo -> combo.getProductItems().removeIf(pi -> pi.getProduct().getId().equals(productId)));
    }

    private CatalogItemRemovalResult removeFromCombos(List<Combo> combos, java.util.function.Consumer<Combo> removeItem) {
        int autoDeactivated = 0;
        for (Combo combo : combos) {
            removeItem.accept(combo);
            if (combo.isActive() && combo.getServiceItems().isEmpty() && combo.getProductItems().isEmpty()) {
                combo.setActive(false);
                autoDeactivated++;
                log.info("Auto-deactivated combo id={} name='{}' — no items left after catalog removal",
                        combo.getId(), combo.getName());
            }
            comboRepository.save(combo);
            log.info("Removed deactivated catalog item from combo id={} name='{}'", combo.getId(), combo.getName());
        }
        return new CatalogItemRemovalResult(combos.size(), autoDeactivated);
    }

    /** Only allowed once deactivated, and only if unreferenced by appointment history. */
    public void permanentlyDelete(Long id) {
        Combo combo = getById(id);
        if (combo.isActive()) {
            throw new IllegalArgumentException("Deactivate this combo before permanently deleting it.");
        }
        if (appointmentComboRepository.existsByCombo_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + combo.getName()
                    + "\" — it is used in one or more appointments.");
        }
        comboRepository.delete(combo);
        log.info("Permanently deleted combo id={} name='{}'", id, combo.getName());
    }

    /** Live sum of current catalog prices across every item — never stored, always computed fresh. */
    public BigDecimal computeOriginalPrice(Combo combo) {
        BigDecimal total = BigDecimal.ZERO;
        for (ComboServiceItem si : combo.getServiceItems()) {
            total = total.add(si.getService().getPrice().multiply(BigDecimal.valueOf(si.getQuantity())));
        }
        for (ComboProductItem pi : combo.getProductItems()) {
            total = total.add(pi.getProduct().getPrice().multiply(BigDecimal.valueOf(pi.getQuantity())));
        }
        return total;
    }

    /** Original price minus the combo's own resolved (capped) discount. */
    public BigDecimal computeComboPrice(Combo combo) {
        BigDecimal original = computeOriginalPrice(combo);
        return original.subtract(computeDiscountAmount(combo, original));
    }

    /** Resolved, capped rupee discount for this combo against the given original price. */
    public BigDecimal computeDiscountAmount(Combo combo, BigDecimal originalPrice) {
        DiscountType type = combo.getDiscountType();
        BigDecimal rawValue = combo.getDiscountValue();
        if (type == null || type == DiscountType.NONE || rawValue == null || rawValue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal resolved = type == DiscountType.PERCENTAGE
                ? originalPrice.multiply(rawValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : rawValue.setScale(2, RoundingMode.HALF_UP);
        return resolved.min(originalPrice);
    }

    /**
     * Builds the autocomplete suggestion DTO for one combo. Relies on open-in-view (enabled
     * project-wide) to lazily fetch serviceItems/productItems within the still-open request-scoped
     * session — acceptable for a small, caller-capped result set (see ComboController.search).
     */
    public ComboSuggestionDTO toSuggestion(Combo combo) {
        BigDecimal original = computeOriginalPrice(combo);
        BigDecimal comboPrice = computeComboPrice(combo);
        return new ComboSuggestionDTO(combo.getId(), combo.getName(), buildItemsSummary(combo),
                original, comboPrice, original.subtract(comboPrice));
    }

    private String buildItemsSummary(Combo combo) {
        List<String> parts = new java.util.ArrayList<>();
        combo.getServiceItems().forEach(si -> parts.add(
                si.getQuantity() > 1 ? si.getQuantity() + "x " + si.getService().getName() : si.getService().getName()));
        combo.getProductItems().forEach(pi -> parts.add(
                pi.getQuantity() > 1 ? pi.getQuantity() + "x " + pi.getProduct().getName() : pi.getProduct().getName()));
        return String.join(" + ", parts);
    }

    private DiscountType resolveDiscountType(String raw) {
        if (raw == null || raw.isBlank()) return DiscountType.NONE;
        try {
            return DiscountType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return DiscountType.NONE;
        }
    }
}
