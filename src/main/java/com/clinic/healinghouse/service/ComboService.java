package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ComboForm;
import com.clinic.healinghouse.dto.ComboSuggestionDTO;
import com.clinic.healinghouse.entity.Combo;
import com.clinic.healinghouse.entity.ComboProductItem;
import com.clinic.healinghouse.entity.ComboServiceItem;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.DiscountType;
import com.clinic.healinghouse.entity.Product;
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

    @Transactional(readOnly = true)
    public List<Combo> findAllActive() {
        return comboRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Page<Combo> search(String query, Pageable pageable) {
        if (StringUtils.hasText(query)) {
            return comboRepository.findByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(query.trim(), pageable);
        }
        return comboRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    /** Includes deactivated combos too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<Combo> findAllIncludingInactive(Pageable pageable) {
        return comboRepository.findAll(pageable);
    }

    /**
     * Unpaginated, for the appointment-form combo picker's autocomplete — caller caps result size.
     * Only offers combos that are still fully selectable (see isSelectable) — a combo whose
     * service/product was deactivated after the combo itself was created shouldn't keep being
     * offered live, even though the combo row itself is still "active".
     */
    @Transactional(readOnly = true)
    public List<Combo> search(String query) {
        if (!StringUtils.hasText(query)) return List.of();
        return comboRepository.findByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(query.trim()).stream()
                .filter(this::isSelectable)
                .toList();
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
