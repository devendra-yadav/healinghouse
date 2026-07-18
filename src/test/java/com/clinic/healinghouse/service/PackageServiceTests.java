package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.PackageAvailabilityDTO;
import com.clinic.healinghouse.dto.PackageSaleForm;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.PatientPackage;
import com.clinic.healinghouse.entity.PatientPackageProductItem;
import com.clinic.healinghouse.entity.PatientPackageServiceItem;
import com.clinic.healinghouse.entity.PatientPackageStatus;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.PackageTemplateRepository;
import com.clinic.healinghouse.repository.PackageTransactionRepository;
import com.clinic.healinghouse.repository.PatientPackageProductItemRepository;
import com.clinic.healinghouse.repository.PatientPackageRepository;
import com.clinic.healinghouse.repository.PatientPackageServiceItemRepository;
import com.clinic.healinghouse.repository.PatientRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageServiceTests {

    @Mock private PatientPackageRepository patientPackageRepository;
    @Mock private PatientPackageServiceItemRepository patientPackageServiceItemRepository;
    @Mock private PatientPackageProductItemRepository patientPackageProductItemRepository;
    @Mock private PackageTransactionRepository packageTransactionRepository;
    @Mock private PackageTemplateRepository packageTemplateRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private EntityManager entityManager;

    private PackageService packageService;

    private static final Long PATIENT_ID = 1L;

    @BeforeEach
    void setUp() {
        packageService = new PackageService(patientPackageRepository, patientPackageServiceItemRepository,
                patientPackageProductItemRepository, packageTransactionRepository, packageTemplateRepository,
                patientRepository, clinicServiceRepository, productRepository, appointmentRepository,
                new HealingHouseProperties(), entityManager);
        lenient().when(patientPackageRepository.save(any(PatientPackage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Patient patient() {
        return Patient.builder().id(PATIENT_ID).fullName("Jane Doe").build();
    }

    private ClinicService service(Long id, BigDecimal price) {
        return ClinicService.builder().id(id).name("Massage").price(price).active(true).build();
    }

    private Product product(Long id, BigDecimal price) {
        return Product.builder().id(id).name("Oil").price(price).active(true).build();
    }

    // ── sellPackage ──────────────────────────────────────────────────────────

    @Test
    void sellPackage_splitsTotalPriceProportionally_andRecordsPurchaseTransaction() {
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient()));
        when(clinicServiceRepository.findById(5L)).thenReturn(Optional.of(service(5L, BigDecimal.valueOf(1000))));

        PackageSaleForm form = new PackageSaleForm();
        form.setPatientId(PATIENT_ID);
        form.setName("10 Session Pack");
        form.setTotalPrice(BigDecimal.valueOf(9000)); // 10% discount off raw 10x1000=10000
        form.setPaymentMethod("CASH");
        PackageSaleForm.PackageSaleItemForm item = new PackageSaleForm.PackageSaleItemForm();
        item.setItemId(5L);
        item.setSessionCount(10);
        form.setServiceItems(List.of(item));

        PatientPackage saved = packageService.sellPackage(form);

        assertThat(saved.getServiceItems()).hasSize(1);
        assertThat(saved.getServiceItems().get(0).getPriceAllocated()).isEqualByComparingTo("9000.00");
        assertThat(saved.getStatus()).isEqualTo(PatientPackageStatus.ACTIVE);

        ArgumentCaptor<com.clinic.healinghouse.entity.PackageTransaction> txn =
                ArgumentCaptor.forClass(com.clinic.healinghouse.entity.PackageTransaction.class);
        verify(packageTransactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("PURCHASE");
        assertThat(txn.getValue().getAmount()).isEqualByComparingTo("9000");
        assertThat(txn.getValue().getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void sellPackage_throws_whenNoItems() {
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient()));

        PackageSaleForm form = new PackageSaleForm();
        form.setPatientId(PATIENT_ID);
        form.setName("Empty Pack");
        form.setTotalPrice(BigDecimal.valueOf(500));
        form.setPaymentMethod("CASH");

        assertThatThrownBy(() -> packageService.sellPackage(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one service or product");

        verify(patientPackageRepository, never()).save(any());
    }

    @Test
    void sellPackage_throws_whenServiceInactive() {
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient()));
        ClinicService inactive = service(5L, BigDecimal.valueOf(1000));
        inactive.setActive(false);
        when(clinicServiceRepository.findById(5L)).thenReturn(Optional.of(inactive));

        PackageSaleForm form = new PackageSaleForm();
        form.setPatientId(PATIENT_ID);
        form.setName("Pack");
        form.setTotalPrice(BigDecimal.valueOf(500));
        form.setPaymentMethod("CASH");
        PackageSaleForm.PackageSaleItemForm item = new PackageSaleForm.PackageSaleItemForm();
        item.setItemId(5L);
        item.setSessionCount(1);
        form.setServiceItems(List.of(item));

        assertThatThrownBy(() -> packageService.sellPackage(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    // ── consume / reverse ────────────────────────────────────────────────────

    private PatientPackage patientPackage() {
        return PatientPackage.builder().id(100L).patient(patient()).name("Pack")
                .totalPrice(BigDecimal.valueOf(1000)).status(PatientPackageStatus.ACTIVE).build();
    }

    @Test
    void consumeServiceItem_incrementsSessionsUsed_andLocksParentPackageBeforeRecordingUsage() {
        PatientPackageServiceItem item = PatientPackageServiceItem.builder()
                .id(1L).patientPackage(patientPackage()).service(service(5L, BigDecimal.valueOf(1000)))
                .sessionsTotal(10).sessionsUsed(3).priceAllocated(BigDecimal.valueOf(1000)).build();
        when(patientPackageServiceItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(appointmentRepository.getReferenceById(50L)).thenReturn(Appointment.builder().id(50L).build());

        packageService.consumeServiceItem(1L, 50L, BigDecimal.valueOf(100));

        assertThat(item.getSessionsUsed()).isEqualTo(4);
        verify(entityManager).lock(item.getPatientPackage(), jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        verify(entityManager).flush();

        ArgumentCaptor<com.clinic.healinghouse.entity.PackageTransaction> txn =
                ArgumentCaptor.forClass(com.clinic.healinghouse.entity.PackageTransaction.class);
        verify(packageTransactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("USAGE");
        assertThat(txn.getValue().getPatientPackageServiceItem()).isEqualTo(item);
    }

    @Test
    void consumeServiceItem_throws_whenNoSessionsRemaining() {
        PatientPackageServiceItem item = PatientPackageServiceItem.builder()
                .id(1L).patientPackage(patientPackage()).service(service(5L, BigDecimal.valueOf(1000)))
                .sessionsTotal(10).sessionsUsed(10).priceAllocated(BigDecimal.valueOf(1000)).build();
        when(patientPackageServiceItemRepository.findById(1L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> packageService.consumeServiceItem(1L, 50L, BigDecimal.valueOf(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No remaining sessions");

        verify(entityManager, never()).lock(any(), any());
        verify(packageTransactionRepository, never()).save(any());
    }

    @Test
    void reverseServiceItem_decrementsSessionsUsed_neverBelowZero() {
        PatientPackageServiceItem item = PatientPackageServiceItem.builder()
                .id(1L).patientPackage(patientPackage()).service(service(5L, BigDecimal.valueOf(1000)))
                .sessionsTotal(10).sessionsUsed(0).priceAllocated(BigDecimal.valueOf(1000)).build();
        when(patientPackageServiceItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(appointmentRepository.getReferenceById(50L)).thenReturn(Appointment.builder().id(50L).build());

        packageService.reverseServiceItem(1L, 50L);

        assertThat(item.getSessionsUsed()).isEqualTo(0); // floored, not negative
        ArgumentCaptor<com.clinic.healinghouse.entity.PackageTransaction> txn =
                ArgumentCaptor.forClass(com.clinic.healinghouse.entity.PackageTransaction.class);
        verify(packageTransactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("REVERSAL");
    }

    // ── refund ───────────────────────────────────────────────────────────────

    @Test
    void refund_throws_whenPackageBelongsToADifferentPatient() {
        PatientPackage pkg = patientPackage();
        pkg.getServiceItems().add(PatientPackageServiceItem.builder()
                .id(1L).patientPackage(pkg).service(service(5L, BigDecimal.valueOf(100)))
                .sessionsTotal(10).sessionsUsed(5).priceAllocated(BigDecimal.valueOf(1000)).build());
        when(patientPackageRepository.findWithServiceItemsById(100L)).thenReturn(Optional.of(pkg));
        when(patientPackageRepository.findWithProductItemsById(100L)).thenReturn(Optional.of(pkg));

        assertThatThrownBy(() -> packageService.refund(999L, 100L, BigDecimal.valueOf(100), PaymentMethod.CASH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to the specified patient");

        verify(patientPackageRepository, never()).saveAndFlush(any());
    }

    @Test
    void refund_throws_whenAmountExceedsRefundableValue() {
        PatientPackage pkg = patientPackage();
        pkg.getServiceItems().add(PatientPackageServiceItem.builder()
                .id(1L).patientPackage(pkg).service(service(5L, BigDecimal.valueOf(100)))
                .sessionsTotal(10).sessionsUsed(5).priceAllocated(BigDecimal.valueOf(1000)).build());
        when(patientPackageRepository.findWithServiceItemsById(100L)).thenReturn(Optional.of(pkg));
        when(patientPackageRepository.findWithProductItemsById(100L)).thenReturn(Optional.of(pkg));

        // refundable = 1000 * 5/10 = 500
        assertThatThrownBy(() -> packageService.refund(PATIENT_ID, 100L, BigDecimal.valueOf(600), PaymentMethod.CASH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed the refundable value");

        verify(patientPackageRepository, never()).saveAndFlush(any());
    }

    @Test
    void refund_success_setsCancelledAndRecordsRefundTransaction() {
        PatientPackage pkg = patientPackage();
        pkg.getServiceItems().add(PatientPackageServiceItem.builder()
                .id(1L).patientPackage(pkg).service(service(5L, BigDecimal.valueOf(100)))
                .sessionsTotal(10).sessionsUsed(5).priceAllocated(BigDecimal.valueOf(1000)).build());
        when(patientPackageRepository.findWithServiceItemsById(100L)).thenReturn(Optional.of(pkg));
        when(patientPackageRepository.findWithProductItemsById(100L)).thenReturn(Optional.of(pkg));
        when(patientPackageRepository.saveAndFlush(any(PatientPackage.class))).thenAnswer(inv -> inv.getArgument(0));

        packageService.refund(PATIENT_ID, 100L, BigDecimal.valueOf(500), PaymentMethod.UPI, "goodwill");

        assertThat(pkg.getStatus()).isEqualTo(PatientPackageStatus.CANCELLED);
        assertThat(pkg.getServiceItems().get(0).getSessionsUsed()).isEqualTo(5); // untouched by refund

        ArgumentCaptor<com.clinic.healinghouse.entity.PackageTransaction> txn =
                ArgumentCaptor.forClass(com.clinic.healinghouse.entity.PackageTransaction.class);
        verify(packageTransactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("REFUND");
        assertThat(txn.getValue().getAmount()).isEqualByComparingTo("500");
    }

    // ── pooled availability (FIFO) ───────────────────────────────────────────

    @Test
    void getPooledAvailability_pools_acrossPurchases_andPicksEarliestAsNextItemId() {
        LocalDate today = LocalDate.now();
        when(patientPackageRepository.findByPatientIdOrderByPurchasedAtDesc(PATIENT_ID)).thenReturn(List.of());

        // Repository already orders by purchasedAt ASC — the mock returns them in that order,
        // so the earliest (first) item for a given service id should become nextItemId.
        PatientPackageServiceItem earliest = PatientPackageServiceItem.builder()
                .id(1L).patientPackage(patientPackage()).service(service(5L, BigDecimal.valueOf(1000)))
                .sessionsTotal(10).sessionsUsed(2).priceAllocated(BigDecimal.valueOf(1000)).build();
        PatientPackageServiceItem later = PatientPackageServiceItem.builder()
                .id(2L).patientPackage(patientPackage()).service(service(5L, BigDecimal.valueOf(1000)))
                .sessionsTotal(5).sessionsUsed(0).priceAllocated(BigDecimal.valueOf(500)).build();
        when(patientPackageServiceItemRepository.findEligibleForPatient(PATIENT_ID, today))
                .thenReturn(List.of(earliest, later));
        when(patientPackageProductItemRepository.findEligibleForPatient(PATIENT_ID, today)).thenReturn(List.of());

        List<PackageAvailabilityDTO> availability = packageService.getPooledAvailability(PATIENT_ID);

        assertThat(availability).hasSize(1);
        PackageAvailabilityDTO dto = availability.get(0);
        assertThat(dto.serviceId()).isEqualTo(5L);
        assertThat(dto.sessionsRemaining()).isEqualTo(8 + 5); // (10-2) + (5-0)
        assertThat(dto.nextItemId()).isEqualTo(1L); // earliest purchase, not item 2
    }
}
