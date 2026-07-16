package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.PackageAvailabilityDTO;
import com.clinic.healinghouse.dto.PackageSaleForm;
import com.clinic.healinghouse.dto.PatientPackageSummaryDTO;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.PackageTemplate;
import com.clinic.healinghouse.entity.PackageTransaction;
import com.clinic.healinghouse.entity.PackageTransactionType;
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
import com.clinic.healinghouse.util.ProportionalAllocator;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages PatientPackage purchases, consumption, refund, and the pooled "Already Paid" appointment
 * form section. Mirrors WalletService's ledger/version-conflict patterns for the money side, and
 * ComboService's proportional-split/live-catalog-price patterns for the catalog side.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PackageService {

    private final PatientPackageRepository patientPackageRepository;
    private final PatientPackageServiceItemRepository patientPackageServiceItemRepository;
    private final PatientPackageProductItemRepository patientPackageProductItemRepository;
    private final PackageTransactionRepository packageTransactionRepository;
    private final PackageTemplateRepository packageTemplateRepository;
    private final PatientRepository patientRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final AppointmentRepository appointmentRepository;
    private final HealingHouseProperties properties;
    private final EntityManager entityManager;

    // ── Selling ──────────────────────────────────────────────────────────────

    public PatientPackage sellPackage(PackageSaleForm form) {
        Patient patient = patientRepository.findById(form.getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("Patient not found: " + form.getPatientId()));

        PackageTemplate sourceTemplate = null;
        if (form.getSourceTemplateId() != null) {
            sourceTemplate = packageTemplateRepository.findById(form.getSourceTemplateId())
                    .orElseThrow(() -> new EntityNotFoundException("Package template not found: " + form.getSourceTemplateId()));
        }

        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("A package name is required.");
        }
        if (form.getTotalPrice() == null || form.getTotalPrice().signum() <= 0) {
            throw new IllegalArgumentException("Total price must be greater than zero.");
        }
        PaymentMethod method = parsePaymentMethod(form.getPaymentMethod());
        if (method == null) {
            throw new IllegalArgumentException("Payment method is required for a package sale.");
        }

        PatientPackage pkg = PatientPackage.builder()
                .patient(patient)
                .sourceTemplate(sourceTemplate)
                .name(form.getName())
                .totalPrice(form.getTotalPrice())
                .expiryDate(form.getExpiryDate())
                .status(PatientPackageStatus.ACTIVE)
                .build();

        List<ProportionalAllocator.AllocationLine> lines = new ArrayList<>();

        for (PackageSaleForm.PackageSaleItemForm item : form.getServiceItems()) {
            if (item == null || item.getItemId() == null) continue;
            ClinicService cs = clinicServiceRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Service not found: " + item.getItemId()));
            if (!cs.isActive()) {
                throw new IllegalArgumentException("Service '" + cs.getName() + "' is inactive and cannot be sold as part of a package.");
            }
            int sessions = Math.max(1, item.getSessionCount());
            BigDecimal raw = cs.getPrice().multiply(BigDecimal.valueOf(sessions));
            PatientPackageServiceItem psi = PatientPackageServiceItem.builder()
                    .patientPackage(pkg)
                    .service(cs)
                    .sessionsTotal(sessions)
                    .priceAllocated(BigDecimal.ZERO)
                    .build();
            pkg.getServiceItems().add(psi);
            lines.add(new ProportionalAllocator.AllocationLine(raw, remainder -> psi.setPriceAllocated(raw.subtract(remainder))));
        }

        for (PackageSaleForm.PackageSaleItemForm item : form.getProductItems()) {
            if (item == null || item.getItemId() == null) continue;
            Product product = productRepository.findById(item.getItemId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found: " + item.getItemId()));
            if (!product.isActive()) {
                throw new IllegalArgumentException("Product '" + product.getName() + "' is inactive and cannot be sold as part of a package.");
            }
            int sessions = Math.max(1, item.getSessionCount());
            BigDecimal raw = product.getPrice().multiply(BigDecimal.valueOf(sessions));
            PatientPackageProductItem ppi = PatientPackageProductItem.builder()
                    .patientPackage(pkg)
                    .product(product)
                    .sessionsTotal(sessions)
                    .priceAllocated(BigDecimal.ZERO)
                    .build();
            pkg.getProductItems().add(ppi);
            lines.add(new ProportionalAllocator.AllocationLine(raw, remainder -> ppi.setPriceAllocated(raw.subtract(remainder))));
        }

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A package must have at least one service or product.");
        }

        BigDecimal rawSum = lines.stream().map(ProportionalAllocator.AllocationLine::rawAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Split totalPrice across items proportional to each item's (catalogPrice x sessionCount)
        // share of rawSum — same allocator Combo/discount distribution uses, with an inverted setter
        // since we want the share itself (priceAllocated), not "raw minus share" (see the allocator's
        // own javadoc on this pattern).
        ProportionalAllocator.distribute(lines, rawSum, form.getTotalPrice());

        PatientPackage saved = patientPackageRepository.save(pkg);

        recordTransaction(saved, PackageTransactionType.PURCHASE, form.getTotalPrice(), method, null, null, null, form.getNote());
        log.info("Sold package id={} patient='{}' totalPrice={}", saved.getId(), patient.getFullName(), saved.getTotalPrice());
        return saved;
    }

    // ── Pooled availability (appointment form "Already Paid" section) ──────────

    @Transactional
    public List<PackageAvailabilityDTO> getPooledAvailability(Long patientId) {
        LocalDate today = LocalDate.now();
        expireStalePackages(patientId, today);

        List<PackageAvailabilityDTO> results = new ArrayList<>();

        Map<Long, long[]> serviceTally = new LinkedHashMap<>(); // serviceId -> [remaining, nextItemId]
        Map<Long, String> serviceNames = new LinkedHashMap<>();
        for (PatientPackageServiceItem item : patientPackageServiceItemRepository.findEligibleForPatient(patientId, today)) {
            Long serviceId = item.getService().getId();
            long[] tally = serviceTally.computeIfAbsent(serviceId, k -> new long[]{0, item.getId()});
            tally[0] += item.getSessionsRemaining();
            serviceNames.putIfAbsent(serviceId, item.getService().getName());
        }
        serviceTally.forEach((serviceId, tally) ->
                results.add(new PackageAvailabilityDTO(serviceId, null, serviceNames.get(serviceId), (int) tally[0], tally[1])));

        Map<Long, long[]> productTally = new LinkedHashMap<>();
        Map<Long, String> productNames = new LinkedHashMap<>();
        for (PatientPackageProductItem item : patientPackageProductItemRepository.findEligibleForPatient(patientId, today)) {
            Long productId = item.getProduct().getId();
            long[] tally = productTally.computeIfAbsent(productId, k -> new long[]{0, item.getId()});
            tally[0] += item.getSessionsRemaining();
            productNames.putIfAbsent(productId, item.getProduct().getName());
        }
        productTally.forEach((productId, tally) ->
                results.add(new PackageAvailabilityDTO(null, productId, productNames.get(productId), (int) tally[0], tally[1])));

        return results;
    }

    /** Flips any ACTIVE package whose expiryDate has passed to EXPIRED. Runs on every availability check — no background job. */
    private void expireStalePackages(Long patientId, LocalDate today) {
        for (PatientPackage pkg : patientPackageRepository.findByPatientIdOrderByPurchasedAtDesc(patientId)) {
            if (pkg.getStatus() == PatientPackageStatus.ACTIVE
                    && pkg.getExpiryDate() != null && pkg.getExpiryDate().isBefore(today)) {
                pkg.setStatus(PatientPackageStatus.EXPIRED);
                patientPackageRepository.save(pkg);
                log.info("Package id={} name='{}' expired (expiryDate={})", pkg.getId(), pkg.getName(), pkg.getExpiryDate());
            }
        }
    }

    // ── Resolution (called from AppointmentService while building lines, before save) ──────

    /**
     * Loads and validates a specific PatientPackageServiceItem is still consumable by this
     * patient — belongs to them, parent package ACTIVE, sessions remaining. Read-only: does not
     * mutate sessionsUsed (that happens in {@link #consumeServiceItem} after the appointment/line
     * is actually saved, mirroring the wallet debit-last ordering). A second check re-runs there
     * too, since another transaction could consume the last session between this resolve call and
     * save — see requirements §5.3's "the save fails with a clear validation error" case.
     */
    @Transactional(readOnly = true)
    public PatientPackageServiceItem resolveServiceItemForConsumption(Long itemId, Long patientId) {
        PatientPackageServiceItem item = patientPackageServiceItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Selected package session is no longer available."));
        validateConsumable(item.getPatientPackage(), patientId, item.getSessionsUsed(), item.getSessionsTotal(), item.getService().getName());
        return item;
    }

    /** Product-item mirror of {@link #resolveServiceItemForConsumption}. */
    @Transactional(readOnly = true)
    public PatientPackageProductItem resolveProductItemForConsumption(Long itemId, Long patientId) {
        PatientPackageProductItem item = patientPackageProductItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Selected package session is no longer available."));
        validateConsumable(item.getPatientPackage(), patientId, item.getSessionsUsed(), item.getSessionsTotal(), item.getProduct().getName());
        return item;
    }

    private void validateConsumable(PatientPackage pkg, Long patientId, int sessionsUsed, int sessionsTotal, String itemName) {
        if (!pkg.getPatient().getId().equals(patientId)) {
            throw new IllegalArgumentException("Selected package session does not belong to this patient.");
        }
        if (pkg.getStatus() != PatientPackageStatus.ACTIVE) {
            throw new IllegalArgumentException("Package '" + pkg.getName() + "' is no longer active.");
        }
        if (sessionsUsed >= sessionsTotal) {
            throw new IllegalArgumentException("No remaining sessions on package item for '" + itemName + "'.");
        }
    }

    // ── Consumption (called from AppointmentService during create/update/cancel/no-show) ─────

    /** Consumes one session from the given service item, linked to appointmentId, worth `amount`. */
    public void consumeServiceItem(Long itemId, Long appointmentId, BigDecimal amount) {
        PatientPackageServiceItem item = patientPackageServiceItemRepository.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Package service item not found: " + itemId));
        if (item.getSessionsUsed() >= item.getSessionsTotal()) {
            throw new IllegalArgumentException("No remaining sessions on package item for '" + item.getService().getName() + "'.");
        }
        item.setSessionsUsed(item.getSessionsUsed() + 1);
        PatientPackage pkg = lockAndPersist(item.getPatientPackage());
        recordTransaction(pkg, PackageTransactionType.USAGE, amount, null, item, null,
                appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Package service item id={} consumed 1 session, appointment id={}", itemId, appointmentId);
    }

    /** Reverses one previously-consumed session on the given service item. */
    public void reverseServiceItem(Long itemId, Long appointmentId) {
        PatientPackageServiceItem item = patientPackageServiceItemRepository.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Package service item not found: " + itemId));
        item.setSessionsUsed(Math.max(0, item.getSessionsUsed() - 1));
        PatientPackage pkg = lockAndPersist(item.getPatientPackage());
        recordTransaction(pkg, PackageTransactionType.REVERSAL, item.getPriceAllocated().signum() > 0
                ? item.getPriceAllocated().divide(BigDecimal.valueOf(Math.max(1, item.getSessionsTotal())), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO, null, item, null, appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Package service item id={} reversed 1 session, appointment id={}", itemId, appointmentId);
    }

    /** Product-item mirror of {@link #consumeServiceItem}. */
    public void consumeProductItem(Long itemId, Long appointmentId, BigDecimal amount) {
        PatientPackageProductItem item = patientPackageProductItemRepository.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Package product item not found: " + itemId));
        if (item.getSessionsUsed() >= item.getSessionsTotal()) {
            throw new IllegalArgumentException("No remaining sessions on package item for '" + item.getProduct().getName() + "'.");
        }
        item.setSessionsUsed(item.getSessionsUsed() + 1);
        PatientPackage pkg = lockAndPersist(item.getPatientPackage());
        recordTransaction(pkg, PackageTransactionType.USAGE, amount, null, null, item,
                appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Package product item id={} consumed 1 session, appointment id={}", itemId, appointmentId);
    }

    /** Product-item mirror of {@link #reverseServiceItem}. */
    public void reverseProductItem(Long itemId, Long appointmentId) {
        PatientPackageProductItem item = patientPackageProductItemRepository.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Package product item not found: " + itemId));
        item.setSessionsUsed(Math.max(0, item.getSessionsUsed() - 1));
        PatientPackage pkg = lockAndPersist(item.getPatientPackage());
        recordTransaction(pkg, PackageTransactionType.REVERSAL, item.getPriceAllocated().signum() > 0
                ? item.getPriceAllocated().divide(BigDecimal.valueOf(Math.max(1, item.getSessionsTotal())), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO, null, null, item, appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Package product item id={} reversed 1 session, appointment id={}", itemId, appointmentId);
    }

    /**
     * The mutated field (sessionsUsed) lives on a child item entity, not on PatientPackage itself,
     * so a plain save() of the parent wouldn't dirty its own row and the @Version column would
     * never be checked or incremented — silently defeating the "guards concurrent consumption"
     * guarantee documented on the entity. OPTIMISTIC_FORCE_INCREMENT is the standard JPA mechanism
     * for "this child mutation should still be treated as an aggregate-root version conflict":
     * it forces a checked UPDATE of the parent's version column at flush time even though none of
     * the parent's own mapped fields changed.
     */
    private PatientPackage lockAndPersist(PatientPackage pkg) {
        try {
            entityManager.lock(pkg, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            entityManager.flush();
        } catch (OptimisticLockException ex) {
            throw new IllegalStateException(
                    "This package was just updated by someone else. Please refresh and try again.", ex);
        }
        refreshStatus(pkg);
        return pkg;
    }

    /** Recomputes ACTIVE/COMPLETED from item counts; never touches a CANCELLED or EXPIRED package. */
    private void refreshStatus(PatientPackage pkg) {
        if (pkg.getStatus() == PatientPackageStatus.CANCELLED || pkg.getStatus() == PatientPackageStatus.EXPIRED) {
            return;
        }
        boolean anyRemaining = pkg.getServiceItems().stream().anyMatch(i -> i.getSessionsRemaining() > 0)
                || pkg.getProductItems().stream().anyMatch(i -> i.getSessionsRemaining() > 0);
        PatientPackageStatus next = anyRemaining ? PatientPackageStatus.ACTIVE : PatientPackageStatus.COMPLETED;
        if (next != pkg.getStatus()) {
            pkg.setStatus(next);
            patientPackageRepository.save(pkg);
        }
    }

    // ── Refund / cancellation ────────────────────────────────────────────────

    public void refund(Long patientPackageId, BigDecimal amount, PaymentMethod method, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero.");
        }
        if (method == null) {
            throw new IllegalArgumentException("Payment method is required for a refund.");
        }
        PatientPackage pkg = getById(patientPackageId);
        BigDecimal refundable = computeRefundableValue(pkg);
        if (amount.compareTo(refundable) > 0) {
            String symbol = properties.getCurrency().getSymbol();
            throw new IllegalArgumentException("Refund amount (" + symbol + amount
                    + ") cannot exceed the refundable value (" + symbol + refundable + ").");
        }
        pkg.setStatus(PatientPackageStatus.CANCELLED);
        try {
            patientPackageRepository.saveAndFlush(pkg);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(
                    "This package was just updated by someone else. Please refresh and try again.", ex);
        }
        recordTransaction(pkg, PackageTransactionType.REFUND, amount, method, null, null, null, note);
        log.info("Refunded package id={} amount={} method={}", pkg.getId(), amount, method);
    }

    /** Σ priceAllocated x sessionsRemaining/sessionsTotal across all items — transient, "derive don't store". */
    public BigDecimal computeRefundableValue(PatientPackage pkg) {
        BigDecimal total = BigDecimal.ZERO;
        for (PatientPackageServiceItem item : pkg.getServiceItems()) {
            total = total.add(itemRefundableValue(item.getPriceAllocated(), item.getSessionsRemaining(), item.getSessionsTotal()));
        }
        for (PatientPackageProductItem item : pkg.getProductItems()) {
            total = total.add(itemRefundableValue(item.getPriceAllocated(), item.getSessionsRemaining(), item.getSessionsTotal()));
        }
        return total;
    }

    private BigDecimal itemRefundableValue(BigDecimal priceAllocated, int remaining, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        return priceAllocated.multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    // ── Reads for the patient detail page ───────────────────────────────────

    /** Two separate queries avoids MultipleBagFetchException — same shape as ComboService.getById. */
    @Transactional(readOnly = true)
    public PatientPackage getById(Long id) {
        PatientPackage pkg = patientPackageRepository.findWithServiceItemsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Package not found: " + id));
        patientPackageRepository.findWithProductItemsById(id); // merges productItems into the L1 cache
        return pkg;
    }

    @Transactional(readOnly = true)
    public List<PatientPackageSummaryDTO> getAllForPatient(Long patientId) {
        return patientPackageRepository.findByPatientIdOrderByPurchasedAtDesc(patientId).stream()
                .map(pkg -> {
                    PatientPackage full = getById(pkg.getId()); // ensure both item collections are loaded
                    List<PatientPackageSummaryDTO.ItemLine> items = new ArrayList<>();
                    full.getServiceItems().forEach(i -> items.add(new PatientPackageSummaryDTO.ItemLine(
                            i.getService().getName(), i.getSessionsTotal(), i.getSessionsUsed(), i.getSessionsRemaining())));
                    full.getProductItems().forEach(i -> items.add(new PatientPackageSummaryDTO.ItemLine(
                            i.getProduct().getName(), i.getSessionsTotal(), i.getSessionsUsed(), i.getSessionsRemaining())));
                    BigDecimal refundable = full.getStatus() == PatientPackageStatus.CANCELLED
                            ? BigDecimal.ZERO : computeRefundableValue(full);
                    return new PatientPackageSummaryDTO(full.getId(), full.getName(), full.getStatus(),
                            full.getTotalPrice(), full.getExpiryDate(), full.getPurchasedAt(), refundable, items);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<PackageTransaction> getTransactionHistoryForPatient(Long patientId, Pageable pageable) {
        return packageTransactionRepository.findByPatientPackage_Patient_IdOrderByCreatedAtDesc(patientId, pageable);
    }

    private void recordTransaction(PatientPackage pkg, PackageTransactionType type, BigDecimal amount,
                                    PaymentMethod method, PatientPackageServiceItem serviceItem,
                                    PatientPackageProductItem productItem,
                                    com.clinic.healinghouse.entity.Appointment appointment, String note) {
        packageTransactionRepository.save(PackageTransaction.builder()
                .patientPackage(pkg)
                .type(type)
                .amount(amount)
                .paymentMethod(method)
                .patientPackageServiceItem(serviceItem)
                .patientPackageProductItem(productItem)
                .appointment(appointment)
                .note(note)
                .build());
    }

    private PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PaymentMethod.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown payment method: " + raw);
        }
    }
}
