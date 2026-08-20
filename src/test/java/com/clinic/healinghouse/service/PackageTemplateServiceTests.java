package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.PackageTemplateForm;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers the per-item price override feature added on top of PackageTemplate's pre-existing
 * "no stored price, always live-computed" catalog pricing — see CLAUDE.md's Packages business
 * rule. Deliberately catalog-only: PackageService.sellPackage's proportional-split math is
 * untouched by this feature (see PackageServiceTests), so it isn't re-tested here.
 */
@ExtendWith(MockitoExtension.class)
class PackageTemplateServiceTests {

    @Mock private PackageTemplateRepository packageTemplateRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AppointmentServiceLineRepository appointmentServiceLineRepository;
    @Mock private AppointmentProductLineRepository appointmentProductLineRepository;
    @Mock private EntityManager entityManager;

    private PackageTemplateService packageTemplateService;

    @BeforeEach
    void setUp() {
        packageTemplateService = new PackageTemplateService(packageTemplateRepository, clinicServiceRepository,
                productRepository, appointmentServiceLineRepository, appointmentProductLineRepository, entityManager);
        lenient().when(packageTemplateRepository.save(any(PackageTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ClinicService service(Long id, BigDecimal price) {
        return ClinicService.builder().id(id).name("Deep Tissue Massage").price(price).active(true).build();
    }

    private Product product(Long id, BigDecimal price) {
        return Product.builder().id(id).name("Massage Oil").price(price).active(true).build();
    }

    // ── computeOriginalPrice ─────────────────────────────────────────────────

    @Test
    void computeOriginalPrice_noOverrides_sumsRawCatalogPriceTimesSessionCount() {
        PackageTemplate template = PackageTemplate.builder().id(1L).name("Test Template").discountType(DiscountType.NONE).build();
        template.setServiceItems(List.of(
                PackageTemplateServiceItem.builder().packageTemplate(template)
                        .service(service(1L, BigDecimal.valueOf(1000))).sessionCount(10).build()));
        template.setProductItems(List.of());

        assertThat(packageTemplateService.computeOriginalPrice(template)).isEqualByComparingTo("10000");
    }

    @Test
    void computeOriginalPrice_withItemOverride_usesOverrideInsteadOfCatalogPrice() {
        PackageTemplate template = PackageTemplate.builder().id(1L).name("Test Template").discountType(DiscountType.NONE).build();
        template.setServiceItems(List.of(
                PackageTemplateServiceItem.builder().packageTemplate(template)
                        .service(service(1L, BigDecimal.valueOf(1000))).sessionCount(10)
                        .priceOverride(BigDecimal.valueOf(800)).build()));
        template.setProductItems(List.of());

        // 800 (overridden) x 10 = 8000, not 1000 x 10 = 10000
        assertThat(packageTemplateService.computeOriginalPrice(template)).isEqualByComparingTo("8000");
    }

    @Test
    void computeSuggestedPrice_layersWholeTemplateDiscountOnTopOfItemLevelOverride() {
        PackageTemplate template = PackageTemplate.builder().id(1L).name("Test Template")
                .discountType(DiscountType.PERCENTAGE).discountValue(BigDecimal.valueOf(10)).build();
        template.setServiceItems(List.of(
                PackageTemplateServiceItem.builder().packageTemplate(template)
                        .service(service(1L, BigDecimal.valueOf(1000))).sessionCount(1)
                        .priceOverride(BigDecimal.valueOf(800)).build()));
        template.setProductItems(List.of());

        // original (item-level) = 800; 10% whole-template discount on top = 80 off => 720
        assertThat(packageTemplateService.computeOriginalPrice(template)).isEqualByComparingTo("800");
        assertThat(packageTemplateService.computeSuggestedPrice(template)).isEqualByComparingTo("720");
    }

    // ── save: clamping ───────────────────────────────────────────────────────

    @Test
    void save_persistsItemPriceOverride_whenWithinCatalogPrice() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        PackageTemplateForm form = new PackageTemplateForm();
        form.setName("Test Template");
        form.setDiscountType("NONE");
        PackageTemplateForm.PackageTemplateItemForm item = new PackageTemplateForm.PackageTemplateItemForm();
        item.setItemId(1L);
        item.setSessionCount(10);
        item.setPrice(BigDecimal.valueOf(750));
        form.setServiceItems(List.of(item));

        PackageTemplate saved = packageTemplateService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isEqualByComparingTo("750");
    }

    @Test
    void save_clampsOverrideAboveCatalogPrice_toNoOverride() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        PackageTemplateForm form = new PackageTemplateForm();
        form.setName("Test Template");
        form.setDiscountType("NONE");
        PackageTemplateForm.PackageTemplateItemForm item = new PackageTemplateForm.PackageTemplateItemForm();
        item.setItemId(1L);
        item.setSessionCount(1);
        item.setPrice(BigDecimal.valueOf(1500)); // above catalog price — must not become a markup
        form.setServiceItems(List.of(item));

        PackageTemplate saved = packageTemplateService.save(form);

        // Clamping to catalog price is "no discount" — must be null, not a stored 1000, so the
        // detail page doesn't show a strikethrough for a line item that was never actually discounted.
        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }

    @Test
    void save_priceEqualToCatalogPrice_leavesOverrideNull() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        PackageTemplateForm form = new PackageTemplateForm();
        form.setName("Test Template");
        form.setDiscountType("NONE");
        PackageTemplateForm.PackageTemplateItemForm item = new PackageTemplateForm.PackageTemplateItemForm();
        item.setItemId(1L);
        item.setSessionCount(1);
        item.setPrice(BigDecimal.valueOf(1000)); // untouched row — form always submits catalog price
        form.setServiceItems(List.of(item));

        PackageTemplate saved = packageTemplateService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }

    @Test
    void save_clampsNegativeOverride_toZero() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, BigDecimal.valueOf(300))));

        PackageTemplateForm form = new PackageTemplateForm();
        form.setName("Test Template");
        form.setDiscountType("NONE");
        PackageTemplateForm.PackageTemplateItemForm item = new PackageTemplateForm.PackageTemplateItemForm();
        item.setItemId(1L);
        item.setSessionCount(1);
        item.setPrice(BigDecimal.valueOf(-50));
        form.setProductItems(List.of(item));

        PackageTemplate saved = packageTemplateService.save(form);

        assertThat(saved.getProductItems().get(0).getPriceOverride()).isEqualByComparingTo("0");
    }

    @Test
    void save_withoutPrice_leavesOverrideNull() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        PackageTemplateForm form = new PackageTemplateForm();
        form.setName("Test Template");
        form.setDiscountType("NONE");
        PackageTemplateForm.PackageTemplateItemForm item = new PackageTemplateForm.PackageTemplateItemForm();
        item.setItemId(1L);
        item.setSessionCount(1);
        form.setServiceItems(List.of(item));

        PackageTemplate saved = packageTemplateService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }
}
