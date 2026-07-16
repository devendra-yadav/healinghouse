package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentServiceLine;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Combo;
import com.clinic.healinghouse.entity.DiscountType;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.PatientPackage;
import com.clinic.healinghouse.entity.PatientPackageServiceItem;
import com.clinic.healinghouse.entity.PatientPackageStatus;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import com.clinic.healinghouse.repository.PatientRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.repository.TherapistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression safety net for AppointmentService's discount/wallet mechanics, written against the
 * current, unrefactored single-phase applyDiscount/distributeDiscount — per Combos_Requirements_v1.md
 * §10, these must still pass unchanged once the two-phase combo refactor lands.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceTests {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private TherapistRepository therapistRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AppointmentServiceLineRepository appointmentServiceLineRepository;
    @Mock private AppointmentProductLineRepository appointmentProductLineRepository;
    @Mock private WalletService walletService;
    @Mock private PackageService packageService;
    @Mock private ComboRepository comboRepository;

    private AppointmentService appointmentService;

    private static final Long PATIENT_ID = 1L;
    private static final Long THERAPIST_ID = 1L;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(appointmentRepository, patientRepository, therapistRepository,
                clinicServiceRepository, productRepository, appointmentServiceLineRepository,
                appointmentProductLineRepository, walletService, packageService, comboRepository, new HealingHouseProperties());
        // lenient: markAsCompleted and updateAppointment's stale-baseline guard-throws-before-lookup
        // tests never reach these patient/therapist lookups.
        lenient().when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient()));
        lenient().when(therapistRepository.findById(THERAPIST_ID)).thenReturn(Optional.of(therapist()));
        // lenient: a few tests (guard-throws-before-save cases) never reach the save() call
        lenient().when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
        // saveAndFlush is used by cancelAppointment/markAsNoShow/updateAppointment's conflict-checked
        // save (AppointmentService.saveWithConflictCheck) — same stub as save() above.
        lenient().when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Combo combo(Long id, DiscountType discountType, BigDecimal discountValue) {
        return Combo.builder().id(id).name("Combo " + id)
                .discountType(discountType).discountValue(discountValue).build();
    }

    private AppointmentForm.ComboSelectionForm comboSelection(Long comboId, String groupKey) {
        AppointmentForm.ComboSelectionForm sel = new AppointmentForm.ComboSelectionForm();
        sel.setComboId(comboId);
        sel.setGroupKey(groupKey);
        return sel;
    }

    private Patient patient() {
        return Patient.builder().id(PATIENT_ID).fullName("Jane Doe").build();
    }

    private Therapist therapist() {
        return Therapist.builder().id(THERAPIST_ID).fullName("Alex Rao").build();
    }

    private ClinicService clinicService(Long id, BigDecimal price) {
        return ClinicService.builder().id(id).name("Service " + id).price(price).build();
    }

    private Product product(Long id, BigDecimal price, int stock) {
        return Product.builder().id(id).name("Product " + id).price(price).stockQuantity(stock).build();
    }

    private AppointmentForm baseForm() {
        AppointmentForm form = new AppointmentForm();
        form.setPatientId(PATIENT_ID);
        form.setTherapistId(THERAPIST_ID);
        form.setAppointmentDateTime(LocalDateTime.now().plusDays(1));
        form.setDurationMinutes(60);
        form.setServiceLines(new ArrayList<>());
        form.setProductLines(new ArrayList<>());
        form.setNewPaymentAmount(BigDecimal.ZERO);
        return form;
    }

    private AppointmentForm.ServiceLineForm serviceLine(Long serviceId, int qty) {
        return serviceLine(serviceId, qty, null);
    }

    private AppointmentForm.ServiceLineForm serviceLine(Long serviceId, int qty, String comboGroupKey) {
        AppointmentForm.ServiceLineForm sl = new AppointmentForm.ServiceLineForm();
        sl.setServiceId(serviceId);
        sl.setQuantity(qty);
        sl.setComboGroupKey(comboGroupKey);
        return sl;
    }

    private AppointmentForm.ProductLineForm productLine(Long productId, int qty) {
        return productLine(productId, qty, null);
    }

    private PatientPackageServiceItem packageServiceItem(Long id, Long serviceId, BigDecimal priceAllocated) {
        return PatientPackageServiceItem.builder().id(id)
                .service(clinicService(serviceId, priceAllocated))
                .patientPackage(PatientPackage.builder().id(200L).status(PatientPackageStatus.ACTIVE).build())
                .sessionsTotal(10).sessionsUsed(0).priceAllocated(priceAllocated).build();
    }

    private AppointmentForm.ServiceLineForm packageServiceLine(Long serviceId, Long packageItemId) {
        AppointmentForm.ServiceLineForm sl = serviceLine(serviceId, 1);
        sl.setPackageItemId(packageItemId);
        return sl;
    }

    private AppointmentForm.ProductLineForm productLine(Long productId, int qty, String comboGroupKey) {
        AppointmentForm.ProductLineForm pl = new AppointmentForm.ProductLineForm();
        pl.setProductId(productId);
        pl.setQuantity(qty);
        pl.setComboGroupKey(comboGroupKey);
        return pl;
    }

    // ── 1. No discount ────────────────────────────────────────────────────────
    @Test
    void createAppointment_noDiscount_grandTotalEqualsRawSubtotal() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(serviceLine(1L, 1));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getGrandTotal()).isEqualByComparingTo("1000");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(saved.getServiceLines().get(0).getDiscountedLineTotal()).isNull();
    }

    // ── 2. Flat discount, remainder absorbed by the last (list-order) line ────
    @Test
    void createAppointment_flatDiscount_sharesSumExactlyAndLastLineAbsorbsRemainder() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1))));
        when(clinicServiceRepository.findById(2L)).thenReturn(Optional.of(clinicService(2L, BigDecimal.valueOf(1))));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, BigDecimal.valueOf(1), 10)));

        AppointmentForm form = baseForm();
        form.setDiscountType("FLAT");
        form.setDiscountValue(BigDecimal.valueOf(1));
        form.getServiceLines().add(serviceLine(1L, 1));
        form.getServiceLines().add(serviceLine(2L, 1));
        form.getProductLines().add(productLine(1L, 1));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("1");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("2"); // subtotal 3 - discount 1

        BigDecimal sumOfShares = saved.getServiceLines().get(0).getLineTotal()
                .subtract(saved.getServiceLines().get(0).getDiscountedLineTotal())
                .add(saved.getServiceLines().get(1).getLineTotal()
                        .subtract(saved.getServiceLines().get(1).getDiscountedLineTotal()))
                .add(saved.getProductLines().get(0).getLineTotal()
                        .subtract(saved.getProductLines().get(0).getDiscountedLineTotal()));
        assertThat(sumOfShares).isEqualByComparingTo("1");

        // Naive proportional rounding would give each of the 3 equal ₹1 lines a 0.33 share (sum 0.99,
        // short by 0.01) — the last-in-list-order line must absorb the remainder to land on 0.34.
        assertThat(saved.getProductLines().get(0).getDiscountedLineTotal()).isEqualByComparingTo("0.66");
    }

    // ── 2b. Rounding remainder redistribution never creates a negative or over-cap share ──
    @Test
    void createAppointment_manyEqualLinesTinyDiscount_noLineShareGoesNegativeOrExceedsRaw() {
        // Regression test for Bug_Report_v2 #5: independently HALF_UP-rounding each non-last line's
        // share can over-allocate the running total by up to ~0.005/line; with enough lines and a
        // discount amount small enough, the old "last line absorbs amount - allocated" logic could
        // drive that line's absorbed share negative — i.e. a "discount" that raised its price above
        // its own raw total. 50 equal ₹1 lines with a ₹0.25 flat discount hits exactly that case
        // (each non-last line's true share is 0.005, which rounds up to 0.01).
        when(clinicServiceRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.of(clinicService(inv.getArgument(0), BigDecimal.ONE)));

        AppointmentForm form = baseForm();
        form.setDiscountType("FLAT");
        form.setDiscountValue(BigDecimal.valueOf(0.25));
        for (long i = 1; i <= 50; i++) {
            form.getServiceLines().add(serviceLine(i, 1));
        }

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("0.25");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("49.75");

        BigDecimal sumOfShares = BigDecimal.ZERO;
        for (var sl : saved.getServiceLines()) {
            BigDecimal share = sl.getLineTotal().subtract(sl.getDiscountedLineTotal());
            assertThat(share).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(share).isLessThanOrEqualTo(sl.getLineTotal());
            sumOfShares = sumOfShares.add(share);
        }
        assertThat(sumOfShares).isEqualByComparingTo("0.25");
    }

    // ── 2c. Duration is capped, keeping findConflicts' pre-filter window provably safe ──
    @Test
    void createAppointment_durationOver24Hours_throws() {
        // Regression test for Bug_Report_v2 #12: findConflicts' DB pre-filter only widens ±1 day
        // around the requested window; an unbounded duration could let a candidate appointment
        // starting more than a day away still overlap without being caught by that pre-filter.
        AppointmentForm form = baseForm();
        form.setDurationMinutes(1441); // 24h + 1min
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.createAppointment(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24 hours");
    }

    // ── 3. Percentage discount capped at 100% ─────────────────────────────────
    @Test
    void createAppointment_percentageOver100_throws() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));

        AppointmentForm form = baseForm();
        form.setDiscountType("PERCENTAGE");
        form.setDiscountValue(BigDecimal.valueOf(150));
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.createAppointment(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 100%");
    }

    // ── 4. Resolved discount capped at subtotal ───────────────────────────────
    @Test
    void createAppointment_flatDiscountExceedingSubtotal_isCappedAtSubtotal() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));

        AppointmentForm form = baseForm();
        form.setDiscountType("FLAT");
        form.setDiscountValue(BigDecimal.valueOf(9999));
        form.getServiceLines().add(serviceLine(1L, 1));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("500");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("0");
    }

    // ── 5. Two consecutive updateAppointment discount changes ────────────────
    @Test
    void updateAppointment_consecutiveDiscountChanges_recomputeCorrectlyEachTime() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));

        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form1 = baseForm();
        form1.setDiscountType("PERCENTAGE");
        form1.setDiscountValue(BigDecimal.valueOf(10));
        form1.getServiceLines().add(serviceLine(1L, 1));

        Appointment afterFirst = appointmentService.updateAppointment(10L, form1);
        assertThat(afterFirst.getDiscountAmount()).isEqualByComparingTo("100");
        assertThat(afterFirst.getGrandTotal()).isEqualByComparingTo("900");
        assertThat(afterFirst.getServiceLines().get(0).getDiscountedLineTotal()).isEqualByComparingTo("900");

        AppointmentForm form2 = baseForm();
        form2.setDiscountType("NONE");
        form2.getServiceLines().add(serviceLine(1L, 1));

        Appointment afterSecond = appointmentService.updateAppointment(10L, form2);
        assertThat(afterSecond.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(afterSecond.getGrandTotal()).isEqualByComparingTo("1000");
        assertThat(afterSecond.getServiceLines().get(0).getDiscountedLineTotal()).isNull();
    }

    // ── 6. Wallet auto-reversal when a discount increase shrinks grandTotal ──
    @Test
    void updateAppointment_discountIncreaseShrinksGrandTotal_reversesWalletExcess() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));

        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .totalServiceAmount(BigDecimal.valueOf(1000))
                .grandTotal(BigDecimal.valueOf(1000))
                .amountPaid(BigDecimal.valueOf(500))
                .walletAmountApplied(BigDecimal.valueOf(500))
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("FLAT");
        form.setDiscountValue(BigDecimal.valueOf(600)); // shrinks grandTotal to 400, below the applied 500
        form.getServiceLines().add(serviceLine(1L, 1));
        // walletAmountApplied left null -> "target" defaults to the previously-applied amount

        Appointment saved = appointmentService.updateAppointment(10L, form);

        assertThat(saved.getGrandTotal()).isEqualByComparingTo("400");
        assertThat(saved.getWalletAmountApplied()).isEqualByComparingTo("400");

        ArgumentCaptor<BigDecimal> reversedAmount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletService).reverseForAppointment(eq(PATIENT_ID), anyLong(), reversedAmount.capture());
        assertThat(reversedAmount.getValue()).isEqualByComparingTo("100");
        verify(walletService, never()).applyToAppointment(any(), anyLong(), any());
    }

    @Test
    void updateAppointment_walletIncrease_appliesTheDelta() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));

        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .totalServiceAmount(BigDecimal.valueOf(1000))
                .grandTotal(BigDecimal.valueOf(1000))
                .amountPaid(BigDecimal.valueOf(100))
                .walletAmountApplied(BigDecimal.valueOf(100))
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setWalletAmountApplied(BigDecimal.valueOf(300));
        form.getServiceLines().add(serviceLine(1L, 1));

        Appointment saved = appointmentService.updateAppointment(10L, form);

        assertThat(saved.getWalletAmountApplied()).isEqualByComparingTo("300");

        ArgumentCaptor<BigDecimal> appliedAmount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletService).applyToAppointment(eq(PATIENT_ID), anyLong(), appliedAmount.capture());
        assertThat(appliedAmount.getValue()).isEqualByComparingTo("200");
        verify(walletService, never()).reverseForAppointment(any(), anyLong(), any());
    }

    // ── 6b. Package-covered lines ─────────────────────────────────────────────
    @Test
    void createAppointment_packageCoveredLine_consumesAfterSaveAndSetsPackageAmountApplied() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        when(packageService.resolveServiceItemForConsumption(500L, PATIENT_ID))
                .thenReturn(packageServiceItem(500L, 1L, BigDecimal.valueOf(1000)));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(packageServiceLine(1L, 500L));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getPackageAmountApplied()).isEqualByComparingTo("1000");
        assertThat(saved.getAmountPaid()).isEqualByComparingTo("1000");
        verify(packageService).consumeServiceItem(eq(500L), any(), eq(BigDecimal.valueOf(1000)));
    }

    @Test
    void createAppointment_twoLinesSamePackageItem_consumesTwiceAndSumsIntoPackageAmountApplied() {
        // Regression coverage for the "click Add twice" case (requirements §2 non-goals) — a single
        // PatientPackageServiceItem can legitimately back more than one line in one appointment.
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        when(packageService.resolveServiceItemForConsumption(500L, PATIENT_ID))
                .thenReturn(packageServiceItem(500L, 1L, BigDecimal.valueOf(1000)));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(packageServiceLine(1L, 500L));
        form.getServiceLines().add(packageServiceLine(1L, 500L));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getPackageAmountApplied()).isEqualByComparingTo("2000");
        verify(packageService, times(2)).consumeServiceItem(eq(500L), any(), eq(BigDecimal.valueOf(1000)));
    }

    @Test
    void updateAppointment_resubmitSamePackageItemId_isNoOpForConsumption() {
        // Acceptance Criteria #7: re-saving an appointment without changing its package-covered
        // lines must not double-consume or spuriously reverse — the trickiest part per §10.
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        PatientPackageServiceItem pkgItem = packageServiceItem(500L, 1L, BigDecimal.valueOf(1000));
        when(packageService.resolveServiceItemForConsumption(500L, PATIENT_ID)).thenReturn(pkgItem);

        AppointmentServiceLine existingLine = AppointmentServiceLine.builder()
                .id(60L).service(clinicService(1L, BigDecimal.valueOf(1000))).therapist(therapist())
                .priceAtTime(BigDecimal.valueOf(1000)).quantity(1)
                .packageServiceItem(pkgItem).build();
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000))
                .amountPaid(BigDecimal.valueOf(1000))
                .packageAmountApplied(BigDecimal.valueOf(1000))
                .build();
        existing.getServiceLines().add(existingLine);
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(packageServiceLine(1L, 500L));

        Appointment saved = appointmentService.updateAppointment(10L, form);

        assertThat(saved.getPackageAmountApplied()).isEqualByComparingTo("1000");
        verify(packageService, never()).consumeServiceItem(any(), any(), any());
        verify(packageService, never()).reverseServiceItem(any(), any());
    }

    @Test
    void updateAppointment_removingPackageLine_reversesConsumption() {
        PatientPackageServiceItem pkgItem = packageServiceItem(500L, 1L, BigDecimal.valueOf(1000));
        AppointmentServiceLine existingLine = AppointmentServiceLine.builder()
                .id(60L).service(clinicService(1L, BigDecimal.valueOf(1000))).therapist(therapist())
                .priceAtTime(BigDecimal.valueOf(1000)).quantity(1)
                .packageServiceItem(pkgItem).build();
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000))
                .amountPaid(BigDecimal.valueOf(1000))
                .packageAmountApplied(BigDecimal.valueOf(1000))
                .build();
        existing.getServiceLines().add(existingLine);
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));
        when(clinicServiceRepository.findById(2L)).thenReturn(Optional.of(clinicService(2L, BigDecimal.valueOf(500))));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(serviceLine(2L, 1)); // different service, no package — replaces the old line

        Appointment saved = appointmentService.updateAppointment(10L, form);

        verify(packageService).reverseServiceItem(500L, 10L);
        assertThat(saved.getPackageAmountApplied()).isEqualByComparingTo("0");
    }

    @Test
    void cancelAppointment_reversesPackageConsumption() {
        PatientPackageServiceItem pkgItem = packageServiceItem(500L, 1L, BigDecimal.valueOf(1000));
        AppointmentServiceLine line = AppointmentServiceLine.builder()
                .id(60L).service(clinicService(1L, BigDecimal.valueOf(1000))).therapist(therapist())
                .priceAtTime(BigDecimal.valueOf(1000)).quantity(1)
                .packageServiceItem(pkgItem).build();
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000))
                .amountPaid(BigDecimal.valueOf(1000))
                .packageAmountApplied(BigDecimal.valueOf(1000))
                .build();
        existing.getServiceLines().add(line);
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        Appointment saved = appointmentService.cancelAppointment(10L, "test");

        verify(packageService).reverseServiceItem(500L, 10L);
        assertThat(saved.getPackageAmountApplied()).isEqualByComparingTo("0");
        assertThat(saved.getAmountPaid()).isEqualByComparingTo("0");
    }

    // ── 7. amountPaid > grandTotal guard ──────────────────────────────────────
    @Test
    void createAppointment_amountPaidExceedsGrandTotal_throws() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setNewPaymentAmount(BigDecimal.valueOf(600));
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.createAppointment(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed the grand total");
    }

    @Test
    void updateAppointment_amountPaidExceedsGrandTotal_throws() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));

        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setNewPaymentAmount(BigDecimal.valueOf(600));
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.updateAppointment(10L, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed the grand total");
    }

    // ── 12 (partial, zero-combo baseline) — proves the pre-refactor formula ──
    @Test
    void createAppointment_zeroComboBaseline_matchesRawSubtotalFormula() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(200))));
        when(clinicServiceRepository.findById(2L)).thenReturn(Optional.of(clinicService(2L, BigDecimal.valueOf(300))));

        AppointmentForm form = baseForm();
        form.setDiscountType("PERCENTAGE");
        form.setDiscountValue(BigDecimal.valueOf(20));
        form.getServiceLines().add(serviceLine(1L, 1));
        form.getServiceLines().add(serviceLine(2L, 1));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("100"); // 20% of 500
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("400");
    }

    // ── 8. Single combo, no manual discount ───────────────────────────────────
    @Test
    void createAppointment_singleCombo_noManualDiscount_appliesComboDiscountOnly() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        when(comboRepository.findById(1L)).thenReturn(Optional.of(combo(1L, DiscountType.FLAT, BigDecimal.valueOf(100))));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getComboSelections().add(comboSelection(1L, "combo-1"));
        form.getServiceLines().add(serviceLine(1L, 1, "combo-1"));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getCombos()).hasSize(1);
        assertThat(saved.getCombos().get(0).getDiscountAmount()).isEqualByComparingTo("100");
        assertThat(saved.getServiceLines().get(0).getDiscountedLineTotal()).isEqualByComparingTo("900");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("0"); // manual (whole-appointment) discount untouched
        assertThat(saved.getTotalComboDiscount()).isEqualByComparingTo("100");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("900");
    }

    // ── 9. Single combo + manual whole-appointment discount layered on top ───
    @Test
    void createAppointment_comboPlusManualDiscount_layersOnEffectiveTotal() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        when(comboRepository.findById(1L)).thenReturn(Optional.of(combo(1L, DiscountType.FLAT, BigDecimal.valueOf(100))));

        AppointmentForm form = baseForm();
        form.setDiscountType("PERCENTAGE");
        form.setDiscountValue(BigDecimal.valueOf(10)); // 10% of the post-combo effective subtotal (900), not raw 1000
        form.getComboSelections().add(comboSelection(1L, "combo-1"));
        form.getServiceLines().add(serviceLine(1L, 1, "combo-1"));

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getCombos().get(0).getDiscountAmount()).isEqualByComparingTo("100");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("90"); // 10% of 900, not 1000
        assertThat(saved.getServiceLines().get(0).getDiscountedLineTotal()).isEqualByComparingTo("810"); // 1000 - 100 - 90
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("810");
    }

    // ── 10. Multiple combos + a standalone line + manual discount ────────────
    @Test
    void createAppointment_multipleCombosPlusStandaloneLinePlusManualDiscount_noDoubleOrUnderDiscounting() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(200))));
        when(clinicServiceRepository.findById(2L)).thenReturn(Optional.of(clinicService(2L, BigDecimal.valueOf(300))));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, BigDecimal.valueOf(500), 10)));
        when(comboRepository.findById(1L)).thenReturn(Optional.of(combo(1L, DiscountType.FLAT, BigDecimal.valueOf(50))));
        when(comboRepository.findById(2L)).thenReturn(Optional.of(combo(2L, DiscountType.PERCENTAGE, BigDecimal.valueOf(10))));

        AppointmentForm form = baseForm();
        form.setDiscountType("FLAT");
        form.setDiscountValue(BigDecimal.valueOf(92));
        form.getComboSelections().add(comboSelection(1L, "combo-1"));
        form.getComboSelections().add(comboSelection(2L, "combo-2"));
        form.getServiceLines().add(serviceLine(1L, 1, "combo-1"));   // 200, combo1 FLAT 50 -> effective 150
        form.getServiceLines().add(serviceLine(2L, 1, "combo-2"));   // 300, combo2 10% -> effective 270
        form.getProductLines().add(productLine(1L, 1, null));       // 500, standalone, no combo discount

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getCombos()).hasSize(2);
        assertThat(saved.getTotalComboDiscount()).isEqualByComparingTo("80"); // 50 + 30
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("92"); // manual layer, over the 920 effective subtotal
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("828"); // 920 - 92

        BigDecimal totalEffective = saved.getServiceLines().get(0).getDiscountedLineTotal()
                .add(saved.getServiceLines().get(1).getDiscountedLineTotal())
                .add(saved.getProductLines().get(0).getDiscountedLineTotal());
        assertThat(totalEffective).isEqualByComparingTo("828");
    }

    // ── 11. Combo removed on update (submitted form omits it) ────────────────
    @Test
    void updateAppointment_comboOmittedFromSubmission_isRemovedAndTotalsRecalculated() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));

        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .build();
        // Simulate a prior save that included a combo — its lines will be wiped along with the combo.
        existing.getCombos().add(com.clinic.healinghouse.entity.AppointmentCombo.builder()
                .appointment(existing).combo(combo(1L, DiscountType.FLAT, BigDecimal.valueOf(100)))
                .comboNameSnapshot("Combo 1").discountType(DiscountType.FLAT)
                .discountValue(BigDecimal.valueOf(100)).discountAmount(BigDecimal.valueOf(100))
                .originalSubtotalSnapshot(BigDecimal.valueOf(1000))
                .build());
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getServiceLines().add(serviceLine(1L, 1)); // standalone only — no comboSelections submitted

        Appointment saved = appointmentService.updateAppointment(10L, form);

        assertThat(saved.getCombos()).isEmpty();
        assertThat(saved.getTotalComboDiscount()).isEqualByComparingTo("0");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("500");
        assertThat(saved.getServiceLines()).hasSize(1);
        assertThat(saved.getServiceLines().get(0).getAppointmentCombo()).isNull();
    }

    // ── Bug_Report_v3 #4: markAsCompleted must re-validate stock, not just decrement blindly ──
    @Test
    void markAsCompleted_insufficientStock_throwsAndDoesNotDecrement() {
        Product product = product(1L, BigDecimal.TEN, 2);
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO)
                .build();
        existing.getProductLines().add(com.clinic.healinghouse.entity.AppointmentProductLine.builder()
                .appointment(existing).product(product).therapist(therapist())
                .quantity(3).priceAtTime(BigDecimal.TEN).lineTotal(BigDecimal.valueOf(30))
                .build());
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));
        when(appointmentRepository.findWithProductLinesById(10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> appointmentService.markAsCompleted(10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(product.getStockQuantity()).isEqualTo(2); // untouched
        assertThat(existing.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED); // untouched
    }

    @Test
    void markAsCompleted_sameProductOnTwoLines_validatesAggregateDemandAndDecrementsBoth() {
        Product product = product(1L, BigDecimal.TEN, 5);
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO)
                .build();
        existing.getProductLines().add(com.clinic.healinghouse.entity.AppointmentProductLine.builder()
                .appointment(existing).product(product).therapist(therapist())
                .quantity(3).priceAtTime(BigDecimal.TEN).lineTotal(BigDecimal.valueOf(30))
                .build());
        existing.getProductLines().add(com.clinic.healinghouse.entity.AppointmentProductLine.builder()
                .appointment(existing).product(product).therapist(therapist())
                .quantity(3).priceAtTime(BigDecimal.TEN).lineTotal(BigDecimal.valueOf(30))
                .build());
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));
        when(appointmentRepository.findWithProductLinesById(10L)).thenReturn(Optional.of(existing));

        // Each individual line (qty 3) is within stock (5), but the combined demand (6) isn't.
        assertThatThrownBy(() -> appointmentService.markAsCompleted(10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void markAsCompleted_sufficientStock_decrementsAndUsesConflictSafeSave() {
        Product product = product(1L, BigDecimal.TEN, 5);
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO)
                .build();
        existing.getProductLines().add(com.clinic.healinghouse.entity.AppointmentProductLine.builder()
                .appointment(existing).product(product).therapist(therapist())
                .quantity(3).priceAtTime(BigDecimal.TEN).lineTotal(BigDecimal.valueOf(30))
                .build());
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));
        when(appointmentRepository.findWithProductLinesById(10L)).thenReturn(Optional.of(existing));

        Appointment saved = appointmentService.markAsCompleted(10L);

        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(product.getStockQuantity()).isEqualTo(2);
        verify(appointmentRepository).saveAndFlush(existing); // saveWithConflictCheck, not plain save()
    }

    // ── Bug_Report_v3 #2: a stale page-load baseline must be rejected, not silently trusted ──
    @Test
    void updateAppointment_staleAmountPaidBaseline_throws() {
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000)).amountPaid(BigDecimal.valueOf(1000)) // someone else already paid it off
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setExistingAmountPaidBaseline(BigDecimal.valueOf(500)); // stale: this page loaded before the other payment
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.updateAppointment(10L, form))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("updated by someone else");
    }

    @Test
    void updateAppointment_staleWalletAppliedBaseline_throws() {
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000)).amountPaid(BigDecimal.valueOf(500))
                .walletAmountApplied(BigDecimal.valueOf(500)) // someone else already changed this
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setExistingWalletAppliedBaseline(BigDecimal.valueOf(200)); // stale
        form.getServiceLines().add(serviceLine(1L, 1));

        assertThatThrownBy(() -> appointmentService.updateAppointment(10L, form))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("updated by someone else");
    }

    @Test
    void updateAppointment_matchingBaseline_proceedsNormally() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(1000))));
        Appointment existing = Appointment.builder()
                .id(10L).patient(patient()).therapist(therapist())
                .status(AppointmentStatus.SCHEDULED)
                .appointmentDateTime(LocalDateTime.now())
                .grandTotal(BigDecimal.valueOf(1000)).amountPaid(BigDecimal.valueOf(500))
                .walletAmountApplied(BigDecimal.valueOf(500))
                .build();
        when(appointmentRepository.findWithServiceLinesById(10L)).thenReturn(Optional.of(existing));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.setExistingAmountPaidBaseline(BigDecimal.valueOf(500)); // matches — not stale
        form.setExistingWalletAppliedBaseline(BigDecimal.valueOf(500)); // matches — not stale
        form.getServiceLines().add(serviceLine(1L, 1));

        Appointment saved = appointmentService.updateAppointment(10L, form);

        assertThat(saved.getGrandTotal()).isEqualByComparingTo("1000");
    }

    // ── Bug_Report_v3 #13: a combo selection with no matching line must not persist as an orphan ──
    @Test
    void createAppointment_comboSelectionWithNoMatchingLines_isDropped() {
        when(clinicServiceRepository.findById(1L)).thenReturn(Optional.of(clinicService(1L, BigDecimal.valueOf(500))));
        when(comboRepository.findById(1L)).thenReturn(Optional.of(combo(1L, DiscountType.FLAT, BigDecimal.valueOf(50))));

        AppointmentForm form = baseForm();
        form.setDiscountType("NONE");
        form.getComboSelections().add(comboSelection(1L, "combo-1")); // selected...
        form.getServiceLines().add(serviceLine(1L, 1)); // ...but no line actually carries "combo-1"

        Appointment saved = appointmentService.createAppointment(form);

        assertThat(saved.getCombos()).isEmpty();
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("500");
    }
}
