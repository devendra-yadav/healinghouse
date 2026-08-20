package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ComboForm;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Combo;
import com.clinic.healinghouse.entity.ComboProductItem;
import com.clinic.healinghouse.entity.ComboServiceItem;
import com.clinic.healinghouse.entity.DiscountType;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.AppointmentComboRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Covers the per-item price override feature added on top of Combo's pre-existing
 * "no stored price, always live-computed" catalog pricing — see CLAUDE.md's Combos business rule.
 */
@ExtendWith(MockitoExtension.class)
class ComboServiceTests {

    @Mock private ComboRepository comboRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AppointmentComboRepository appointmentComboRepository;
    @Mock private EntityManager entityManager;

    private ComboService comboService;

    @BeforeEach
    void setUp() {
        comboService = new ComboService(comboRepository, clinicServiceRepository, productRepository,
                appointmentComboRepository, entityManager);
        lenient().when(comboRepository.save(any(Combo.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ClinicService service(Long id, BigDecimal price) {
        return ClinicService.builder().id(id).name("Deep Tissue Massage").price(price).active(true).build();
    }

    private Product product(Long id, BigDecimal price) {
        return Product.builder().id(id).name("Massage Oil").price(price).active(true).build();
    }

    // ── computeOriginalPrice ─────────────────────────────────────────────────

    @Test
    void computeOriginalPrice_noOverrides_sumsRawCatalogPrices() {
        Combo combo = Combo.builder().id(1L).name("Test Combo").discountType(DiscountType.NONE).build();
        combo.setServiceItems(List.of(
                ComboServiceItem.builder().combo(combo).service(service(1L, BigDecimal.valueOf(1000))).quantity(2).build()));
        combo.setProductItems(List.of(
                ComboProductItem.builder().combo(combo).product(product(1L, BigDecimal.valueOf(300))).quantity(1).build()));

        assertThat(comboService.computeOriginalPrice(combo)).isEqualByComparingTo("2300");
    }

    @Test
    void computeOriginalPrice_withItemOverride_usesOverrideInsteadOfCatalogPrice() {
        Combo combo = Combo.builder().id(1L).name("Test Combo").discountType(DiscountType.NONE).build();
        combo.setServiceItems(List.of(
                ComboServiceItem.builder().combo(combo).service(service(1L, BigDecimal.valueOf(1000)))
                        .quantity(2).priceOverride(BigDecimal.valueOf(800)).build()));
        combo.setProductItems(List.of());

        // 800 (overridden) x 2 = 1600, not 1000 x 2 = 2000
        assertThat(comboService.computeOriginalPrice(combo)).isEqualByComparingTo("1600");
    }

    @Test
    void computeComboPrice_layersWholeComboDiscountOnTopOfItemLevelOverride() {
        Combo combo = Combo.builder().id(1L).name("Test Combo")
                .discountType(DiscountType.PERCENTAGE).discountValue(BigDecimal.valueOf(10)).build();
        combo.setServiceItems(List.of(
                ComboServiceItem.builder().combo(combo).service(service(1L, BigDecimal.valueOf(1000)))
                        .quantity(1).priceOverride(BigDecimal.valueOf(800)).build()));
        combo.setProductItems(List.of());

        // original (item-level) = 800; 10% whole-combo discount on top = 80 off => 720
        assertThat(comboService.computeOriginalPrice(combo)).isEqualByComparingTo("800");
        assertThat(comboService.computeComboPrice(combo)).isEqualByComparingTo("720");
    }

    // ── save: clamping ───────────────────────────────────────────────────────

    @Test
    void save_persistsItemPriceOverride_whenWithinCatalogPrice() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        ComboForm form = new ComboForm();
        form.setName("Test Combo");
        form.setDiscountType("NONE");
        ComboForm.ComboItemForm item = new ComboForm.ComboItemForm();
        item.setItemId(1L);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(750));
        form.setServiceItems(List.of(item));

        Combo saved = comboService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isEqualByComparingTo("750");
    }

    @Test
    void save_clampsOverrideAboveCatalogPrice_toNoOverride() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        ComboForm form = new ComboForm();
        form.setName("Test Combo");
        form.setDiscountType("NONE");
        ComboForm.ComboItemForm item = new ComboForm.ComboItemForm();
        item.setItemId(1L);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(1500)); // above catalog price — must not become a markup
        form.setServiceItems(List.of(item));

        Combo saved = comboService.save(form);

        // Clamping to catalog price is "no discount" — must be null, not a stored 1000, so the
        // detail page doesn't show a strikethrough for a line item that was never actually discounted.
        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }

    @Test
    void save_priceEqualToCatalogPrice_leavesOverrideNull() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        ComboForm form = new ComboForm();
        form.setName("Test Combo");
        form.setDiscountType("NONE");
        ComboForm.ComboItemForm item = new ComboForm.ComboItemForm();
        item.setItemId(1L);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(1000)); // untouched row — form always submits catalog price
        form.setServiceItems(List.of(item));

        Combo saved = comboService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }

    @Test
    void save_clampsNegativeOverride_toZero() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, BigDecimal.valueOf(300))));

        ComboForm form = new ComboForm();
        form.setName("Test Combo");
        form.setDiscountType("NONE");
        ComboForm.ComboItemForm item = new ComboForm.ComboItemForm();
        item.setItemId(1L);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(-50));
        form.setProductItems(List.of(item));

        Combo saved = comboService.save(form);

        assertThat(saved.getProductItems().get(0).getPriceOverride()).isEqualByComparingTo("0");
    }

    @Test
    void save_withoutPrice_leavesOverrideNull() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(service(1L, BigDecimal.valueOf(1000))));

        ComboForm form = new ComboForm();
        form.setName("Test Combo");
        form.setDiscountType("NONE");
        ComboForm.ComboItemForm item = new ComboForm.ComboItemForm();
        item.setItemId(1L);
        item.setQuantity(1);
        form.setServiceItems(List.of(item));

        Combo saved = comboService.save(form);

        assertThat(saved.getServiceItems().get(0).getPriceOverride()).isNull();
    }
}
