package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.PackageTemplateDetailDTO;
import com.clinic.healinghouse.dto.PackageTemplateForm;
import com.clinic.healinghouse.dto.PackageTemplateSuggestionDTO;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.DiscountType;
import com.clinic.healinghouse.entity.PackageTemplate;
import com.clinic.healinghouse.entity.PackageTemplateProductItem;
import com.clinic.healinghouse.entity.PackageTemplateServiceItem;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.PackageTemplateRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the (optional, staff-managed) PackageTemplate catalog — mirrors ComboService closely:
 * same soft-delete/permanent-delete lifecycle, same "suggested price is always computed live from
 * current catalog prices, never stored" philosophy.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PackageTemplateService {

    private final PackageTemplateRepository packageTemplateRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final AppointmentServiceLineRepository appointmentServiceLineRepository;
    private final AppointmentProductLineRepository appointmentProductLineRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<PackageTemplate> findAllActive() {
        return packageTemplateRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Page<PackageTemplate> search(String query, Pageable pageable) {
        if (StringUtils.hasText(query)) {
            return packageTemplateRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim(), pageable);
        }
        return packageTemplateRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    /** Includes deactivated templates too — backs the list page's "Show inactive" toggle. */
    @Transactional(readOnly = true)
    public Page<PackageTemplate> findAllIncludingInactive(Pageable pageable) {
        return packageTemplateRepository.findAll(pageable);
    }

    /** Two separate queries avoids MultipleBagFetchException — same shape as ComboService.getById. */
    @Transactional(readOnly = true)
    public PackageTemplate getById(Long id) {
        PackageTemplate template = packageTemplateRepository.findWithServiceItemsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Package template not found: " + id));
        packageTemplateRepository.findWithProductItemsById(id); // merges productItems into the L1 cache
        return template;
    }

    public PackageTemplate save(PackageTemplateForm form) {
        // PackageTemplate.name is @NotBlank — checked here (not left to JPA's flush-time validation)
        // so saveAndRedirect's IllegalArgumentException catch handles it with a friendly re-render
        // instead of a ConstraintViolationException reaching the generic error page (Bug_Report_v4.md #10).
        if (!StringUtils.hasText(form.getName())) {
            throw new IllegalArgumentException("Name is required.");
        }
        PackageTemplate template = form.getId() != null ? getById(form.getId()) : PackageTemplate.builder().build();
        boolean isNew = template.getId() == null;

        template.setName(form.getName());
        template.setDescription(form.getDescription());
        template.setActive(form.isActive());

        template.getServiceItems().clear();
        for (PackageTemplateForm.PackageTemplateItemForm item : form.getServiceItems()) {
            if (item == null || item.getItemId() == null) continue;
            ClinicService cs = clinicServiceRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Service not found: " + item.getItemId()));
            if (!cs.isActive()) {
                throw new IllegalArgumentException("Service '" + cs.getName() + "' is inactive and cannot be added to a package template.");
            }
            template.getServiceItems().add(
                    PackageTemplateServiceItem.builder()
                            .packageTemplate(template)
                            .service(cs)
                            .sessionCount(Math.max(1, item.getSessionCount()))
                            .priceOverride(clampOverride(item.getPrice(), cs.getPrice()))
                            .build());
        }

        template.getProductItems().clear();
        for (PackageTemplateForm.PackageTemplateItemForm item : form.getProductItems()) {
            if (item == null || item.getItemId() == null) continue;
            Product product = productRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found: " + item.getItemId()));
            if (!product.isActive()) {
                throw new IllegalArgumentException("Product '" + product.getName() + "' is inactive and cannot be added to a package template.");
            }
            template.getProductItems().add(
                    PackageTemplateProductItem.builder()
                            .packageTemplate(template)
                            .product(product)
                            .sessionCount(Math.max(1, item.getSessionCount()))
                            .priceOverride(clampOverride(item.getPrice(), product.getPrice()))
                            .build());
        }

        if (template.getServiceItems().isEmpty() && template.getProductItems().isEmpty()) {
            throw new IllegalArgumentException("A package template must have at least one service or product.");
        }

        DiscountType type = resolveDiscountType(form.getDiscountType());
        if (type != DiscountType.NONE) {
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
        template.setDiscountType(type);
        template.setDiscountValue(type == DiscountType.NONE ? null : form.getDiscountValue());

        PackageTemplate saved = packageTemplateRepository.save(template);
        log.info("{} package template id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    public void deactivate(Long id) {
        PackageTemplate template = getById(id);
        template.setActive(false);
        packageTemplateRepository.save(template);
        log.info("Deactivated package template id={} name='{}'", template.getId(), template.getName());
    }

    public void activate(Long id) {
        PackageTemplate template = getById(id);
        template.setActive(true);
        packageTemplateRepository.save(template);
        log.info("Reactivated package template id={} name='{}'", template.getId(), template.getName());
    }

    /**
     * Only allowed once deactivated, and only if no package sold from this template has ever been
     * consumed in an appointment — checked via the AppointmentServiceLine/ProductLine ->
     * PatientPackageServiceItem/ProductItem -> PatientPackage.sourceTemplate chain, mirroring
     * ComboService.permanentlyDelete's "unreferenced by appointment history" guard.
     */
    public void permanentlyDelete(Long id) {
        PackageTemplate template = getById(id);
        if (template.isActive()) {
            throw new IllegalArgumentException("Deactivate this package template before permanently deleting it.");
        }
        if (appointmentServiceLineRepository.existsByPackageServiceItem_PatientPackage_SourceTemplate_Id(id)
                || appointmentProductLineRepository.existsByPackageProductItem_PatientPackage_SourceTemplate_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + template.getName()
                    + "\" — a package sold from it has been used in one or more appointments.");
        }
        packageTemplateRepository.delete(template);
        log.info("Permanently deleted package template id={} name='{}'", id, template.getName());
    }

    /** Summary handed back to the caller (TreatmentService/ProductService) to build a flash message
     *  — mirrors ComboService.CatalogItemRemovalResult exactly. */
    public record CatalogItemRemovalResult(int templatesAffected, int templatesAutoDeactivated) {
        public boolean isEmpty() {
            return templatesAffected == 0;
        }

        /** Flash-message suffix, e.g. " Removed from 1 package template(s); 1 auto-deactivated (no items left)." */
        public String describe() {
            if (isEmpty()) return "";
            String msg = " Removed from " + templatesAffected + " package template(s)";
            if (templatesAutoDeactivated > 0) {
                msg += "; " + templatesAutoDeactivated + " auto-deactivated (no items left)";
            }
            return msg + ".";
        }
    }

    /**
     * Called when a ClinicService is deactivated: strips it out of every package template that
     * bundles it and auto-deactivates any template left with zero items — mirrors {@link
     * ComboService#handleServiceDeactivated} and the same "at least one item" invariant {@link
     * #save} enforces. Without this, a template kept referencing the now-inactive item, its
     * live-computed price stayed built from the stale item, and it stayed listed as active/
     * sellable while every sale attempt from it failed with no admin-facing signal why
     * (Bug_Report_v6.md Finding 9).
     */
    public CatalogItemRemovalResult handleServiceDeactivated(Long serviceId) {
        List<PackageTemplate> templates = packageTemplateRepository.findByServiceItems_Service_Id(serviceId);
        return removeFromTemplates(templates, t -> t.getServiceItems().removeIf(si -> si.getService().getId().equals(serviceId)));
    }

    /** Product equivalent of {@link #handleServiceDeactivated}. */
    public CatalogItemRemovalResult handleProductDeactivated(Long productId) {
        List<PackageTemplate> templates = packageTemplateRepository.findByProductItems_Product_Id(productId);
        return removeFromTemplates(templates, t -> t.getProductItems().removeIf(pi -> pi.getProduct().getId().equals(productId)));
    }

    /**
     * The "empty after removal" check below reads each template's in-transaction collection state,
     * which two concurrent deactivations (one removing the template's last service, the other its
     * last product) can both evaluate against stale data under MySQL's default REPEATABLE READ —
     * each sees the other item still present, so neither trips the auto-deactivate branch and the
     * template silently ends up with zero items while still active. This mirrors exactly the race
     * Bug_Report_v6.md Finding 10 fixed for Combo — that fix was never applied here even though
     * this method already mirrored ComboService.removeFromCombos in every other respect
     * (Bug_Report_v7.md Finding 10). lockAndPersist force-increments the version on every template
     * touched here, so the second concurrent call to reach it always loses with a clear "just
     * updated" error instead of silently completing.
     */
    private CatalogItemRemovalResult removeFromTemplates(List<PackageTemplate> templates, java.util.function.Consumer<PackageTemplate> removeItem) {
        int autoDeactivated = 0;
        for (PackageTemplate template : templates) {
            removeItem.accept(template);
            if (template.isActive() && template.getServiceItems().isEmpty() && template.getProductItems().isEmpty()) {
                template.setActive(false);
                autoDeactivated++;
                log.info("Auto-deactivated package template id={} name='{}' — no items left after catalog removal",
                        template.getId(), template.getName());
            }
            lockAndPersist(template);
            log.info("Removed deactivated catalog item from package template id={} name='{}'", template.getId(), template.getName());
        }
        return new CatalogItemRemovalResult(templates.size(), autoDeactivated);
    }

    private void lockAndPersist(PackageTemplate template) {
        try {
            packageTemplateRepository.save(template);
            entityManager.lock(template, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            entityManager.flush();
        } catch (OptimisticLockException ex) {
            throw new IllegalStateException(
                    "This package template was just updated by someone else. Please refresh and try again.", ex);
        }
    }

    /**
     * Sum of each item's effective unit price (its own priceOverride if staff set one — discount
     * only, clamped to catalog price at save time — else the live catalog price) x session count,
     * across every item. Never stored. Already reflects any per-item discount; the template's own
     * whole-bundle discount (computeDiscountAmount/computeSuggestedPrice) layers on top of this,
     * mirroring ComboService.computeOriginalPrice's identical two-phase reasoning.
     */
    public BigDecimal computeOriginalPrice(PackageTemplate template) {
        BigDecimal total = BigDecimal.ZERO;
        for (PackageTemplateServiceItem si : template.getServiceItems()) {
            BigDecimal rate = si.getPriceOverride() != null ? si.getPriceOverride() : si.getService().getPrice();
            total = total.add(rate.multiply(BigDecimal.valueOf(si.getSessionCount())));
        }
        for (PackageTemplateProductItem pi : template.getProductItems()) {
            BigDecimal rate = pi.getPriceOverride() != null ? pi.getPriceOverride() : pi.getProduct().getPrice();
            total = total.add(rate.multiply(BigDecimal.valueOf(pi.getSessionCount())));
        }
        return total;
    }

    /**
     * True catalog total — session count x each item's live catalog price, ignoring any
     * priceOverride. Used only where "Savings" needs to mean "everything saved vs. buying each
     * session separately at list price" — a per-item priceOverride is a real discount exactly like
     * the template-level one, so it must count towards Savings too. computeOriginalPrice deliberately
     * stays as-is (it's the base computeSuggestedPrice/computeDiscountAmount resolve the template-level
     * discount against), so per-item discounts don't get silently netted out of the displayed savings.
     */
    public BigDecimal computeCatalogPrice(PackageTemplate template) {
        BigDecimal total = BigDecimal.ZERO;
        for (PackageTemplateServiceItem si : template.getServiceItems()) {
            total = total.add(si.getService().getPrice().multiply(BigDecimal.valueOf(si.getSessionCount())));
        }
        for (PackageTemplateProductItem pi : template.getProductItems()) {
            total = total.add(pi.getProduct().getPrice().multiply(BigDecimal.valueOf(pi.getSessionCount())));
        }
        return total;
    }

    /**
     * Clamps a staff-entered per-item price to [0, catalogPrice] — discount only, never a markup.
     * Null/blank input, or a clamped value equal to the catalog price, both mean "no override" (the
     * form's rate input always carries a numeric value — defaulting to catalog price on an untouched
     * row — so treating "equals catalog price" as null here is what stops every row from showing a
     * strikethrough on the detail page just because the form always submits a price).
     */
    private BigDecimal clampOverride(BigDecimal requested, BigDecimal catalogPrice) {
        if (requested == null) return null;
        BigDecimal clamped = requested.max(BigDecimal.ZERO).min(catalogPrice);
        return clamped.compareTo(catalogPrice) == 0 ? null : clamped;
    }

    /** Original price minus the template's own resolved (capped) discount — a starting point only, never binding. */
    public BigDecimal computeSuggestedPrice(PackageTemplate template) {
        BigDecimal original = computeOriginalPrice(template);
        return original.subtract(computeDiscountAmount(template, original));
    }

    public BigDecimal computeDiscountAmount(PackageTemplate template, BigDecimal originalPrice) {
        DiscountType type = template.getDiscountType();
        BigDecimal rawValue = template.getDiscountValue();
        if (type == null || type == DiscountType.NONE || rawValue == null || rawValue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal resolved = type == DiscountType.PERCENTAGE
                ? originalPrice.multiply(rawValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : rawValue.setScale(2, RoundingMode.HALF_UP);
        return resolved.min(originalPrice);
    }

    public PackageTemplateSuggestionDTO toSuggestion(PackageTemplate template) {
        return new PackageTemplateSuggestionDTO(template.getId(), template.getName(),
                buildItemsSummary(template), computeSuggestedPrice(template));
    }

    /** Plain-data projection for the sell-package modal's template picker JS — see the DTO's javadoc. */
    public PackageTemplateDetailDTO toDetailDTO(PackageTemplate template) {
        List<PackageTemplateDetailDTO.ItemLine> serviceItems = template.getServiceItems().stream()
                .map(si -> new PackageTemplateDetailDTO.ItemLine(si.getService().getId(), si.getSessionCount()))
                .toList();
        List<PackageTemplateDetailDTO.ItemLine> productItems = template.getProductItems().stream()
                .map(pi -> new PackageTemplateDetailDTO.ItemLine(pi.getProduct().getId(), pi.getSessionCount()))
                .toList();
        return new PackageTemplateDetailDTO(template.getId(), template.getName(),
                computeSuggestedPrice(template), serviceItems, productItems);
    }

    /** Human-readable item list, e.g. "10x Deep Tissue Massage" — used by the picker suggestion DTO
     *  and the list page's CSV/PDF export. */
    public String buildItemsSummary(PackageTemplate template) {
        List<String> parts = new ArrayList<>();
        template.getServiceItems().forEach(si -> parts.add(si.getSessionCount() + "x " + si.getService().getName()));
        template.getProductItems().forEach(pi -> parts.add(pi.getSessionCount() + "x " + pi.getProduct().getName()));
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
