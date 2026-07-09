package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AppointmentService {

    private final AppointmentRepository            appointmentRepository;
    private final PatientRepository                patientRepository;
    private final TherapistRepository              therapistRepository;
    private final ClinicServiceRepository          clinicServiceRepository;
    private final ProductRepository                productRepository;
    private final AppointmentServiceLineRepository appointmentServiceLineRepository;
    private final AppointmentProductLineRepository appointmentProductLineRepository;

    private static final Sort DATE_DESC =
            Sort.by(Sort.Direction.DESC, "appointmentDateTime");

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Loads appointment with all associations (for detail / edit pages).
     * Two separate JPQL queries run inside the same Hibernate session so the
     * second query merges productLines into the already-cached Appointment,
     * avoiding MultipleBagFetchException on the two @OneToMany bag collections.
     */
    @Transactional(readOnly = true)
    public Appointment getById(Long id) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        appointmentRepository.findWithProductLinesById(id); // initialises productLines in L1 cache
        return appt;
    }

    /**
     * Filtered list — all parameters are optional (null = no filter).
     * Uses JPA Specifications so each filter is independently optional.
     * Patient and therapist are JOIN FETCHed to avoid N+1 on the list page.
     */
    @Transactional(readOnly = true)
    public List<Appointment> findByFilters(AppointmentStatus status,
                                           Long therapistId,
                                           LocalDate dateFrom,
                                           LocalDate dateTo,
                                           String patientName) {
        return findByFilters(status, therapistId, dateFrom, dateTo, patientName, null);
    }

    /** Same as above, additionally scoped to a single patient (used by the Patient Detail history table). */
    @Transactional(readOnly = true)
    public List<Appointment> findByFilters(AppointmentStatus status,
                                           Long therapistId,
                                           LocalDate dateFrom,
                                           LocalDate dateTo,
                                           String patientName,
                                           Long patientId) {
        LocalDateTime start = dateFrom != null ? dateFrom.atStartOfDay()      : null;
        LocalDateTime end   = dateTo   != null ? dateTo.atTime(LocalTime.MAX) : null;

        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.hasStatus(status))
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(start, end))
                .and(AppointmentSpec.patientNameOrPhoneContains(patientName))
                .and(AppointmentSpec.hasPatientId(patientId));

        return appointmentRepository.findAll(spec, DATE_DESC);
    }

    /** Shortcut: all appointments ordered by date descending (no filter). */
    @Transactional(readOnly = true)
    public List<Appointment> findAll() {
        return findByFilters(null, null, null, null, null);
    }

    /** Today's appointments ordered by time (for dashboard). */
    @Transactional(readOnly = true)
    public List<Appointment> findTodayAppointments() {
        LocalDate today = LocalDate.now();
        return appointmentRepository.findTodayAppointments(
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay());
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates an appointment from the form DTO.
     * Rules enforced:
     *   • At least one service line required.
     *   • Product quantity must not exceed available stock.
     *   • Prices are snapshotted at the time of creation.
     *   • Product stock is decremented immediately.
     */
    public Appointment createAppointment(AppointmentForm form) {

        // 1. Load patient & therapist
        Patient patient = patientRepository.findById(form.getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("Patient not found"));
        Therapist therapist = therapistRepository.findById(form.getTherapistId())
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));

        // 2. Must have at least one service
        List<AppointmentForm.ServiceLineForm> rawServices = form.getServiceLines().stream()
                .filter(s -> s != null && s.getServiceId() != null)
                .toList();
        if (rawServices.isEmpty()) {
            throw new IllegalArgumentException("At least one service must be selected.");
        }

        // 3. Build appointment shell (lines added below)
        PaymentMethod paymentMethod = null;
        if (form.getPaymentMethod() != null && !form.getPaymentMethod().isBlank()) {
            try {
                paymentMethod = PaymentMethod.valueOf(form.getPaymentMethod().trim());
            } catch (IllegalArgumentException ignored) {}
        }

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .therapist(therapist)
                .appointmentDateTime(form.getAppointmentDateTime())
                .notes(form.getNotes())
                .paymentMethod(paymentMethod)
                .amountPaid(form.getAmountPaid() != null ? form.getAmountPaid() : BigDecimal.ZERO)
                .build();

        // 4. Service lines — snapshot price at booking time
        BigDecimal totalServiceAmount = BigDecimal.ZERO;
        for (AppointmentForm.ServiceLineForm slf : rawServices) {
            ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Service not found: " + slf.getServiceId()));
            int qty = Math.max(1, slf.getQuantity());
            BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));

            appointment.getServiceLines().add(
                    AppointmentServiceLine.builder()
                            .appointment(appointment)
                            .service(cs)
                            .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                            .priceAtTime(cs.getPrice())
                            .quantity(qty)
                            .build());

            totalServiceAmount = totalServiceAmount.add(lineTotal);
        }

        // 5. Product lines — validate stock, snapshot price, decrement stock
        BigDecimal totalProductAmount = BigDecimal.ZERO;
        for (AppointmentForm.ProductLineForm plf : form.getProductLines()) {
            if (plf == null || plf.getProductId() == null) continue;
            int qty = Math.max(1, plf.getQuantity());

            Product product = productRepository.findById(plf.getProductId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product not found: " + plf.getProductId()));

            if (product.getStockQuantity() < qty) {
                throw new IllegalArgumentException(
                        "Insufficient stock for '" + product.getName()
                        + "'. Available: " + product.getStockQuantity()
                        + ", requested: " + qty);
            }

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));

            appointment.getProductLines().add(
                    AppointmentProductLine.builder()
                            .appointment(appointment)
                            .product(product)
                            .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                            .quantity(qty)
                            .priceAtTime(product.getPrice())
                            .lineTotal(lineTotal)
                            .build());

            // Decrement stock — treatment has been administered
            product.setStockQuantity(product.getStockQuantity() - qty);
            log.info("Stock decremented for product id={} name='{}' by {} (remaining={})",
                    product.getId(), product.getName(), qty, product.getStockQuantity());

            totalProductAmount = totalProductAmount.add(lineTotal);
        }

        // 6. Set aggregate totals
        appointment.setTotalServiceAmount(totalServiceAmount);
        appointment.setTotalProductAmount(totalProductAmount);
        appointment.setGrandTotal(totalServiceAmount.add(totalProductAmount));

        Appointment saved = appointmentRepository.save(appointment);
        log.info("Created appointment id={} patient='{}' therapist='{}' grandTotal={}",
                saved.getId(), patient.getFullName(), therapist.getFullName(), saved.getGrandTotal());
        return saved;
    }

    // ── Status transitions ────────────────────────────────────────────────────

    /** SCHEDULED → COMPLETED. Sets completedAt timestamp. */
    public Appointment markAsCompleted(Long id) {
        Appointment appt = appointmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Only SCHEDULED appointments can be marked as completed.");
        }
        appt.setStatus(AppointmentStatus.COMPLETED);
        appt.setCompletedAt(LocalDateTime.now());
        Appointment saved = appointmentRepository.save(appt);
        log.info("Appointment id={} marked COMPLETED", saved.getId());
        return saved;
    }

    /** SCHEDULED → CANCELLED. Stores reason and restores product stock. */
    public Appointment cancelAppointment(Long id, String reason) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        appointmentRepository.findWithProductLinesById(id); // needed by restoreProductStock
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Only SCHEDULED appointments can be cancelled.");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setCancelReason(reason);
        restoreProductStock(appt);
        Appointment saved = appointmentRepository.save(appt);
        log.info("Appointment id={} marked CANCELLED reason='{}'", saved.getId(), reason);
        return saved;
    }

    /** SCHEDULED → NO_SHOW. Restores product stock (patient never arrived). */
    public Appointment markAsNoShow(Long id) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        appointmentRepository.findWithProductLinesById(id); // needed by restoreProductStock
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Only SCHEDULED appointments can be marked as no-show.");
        }
        appt.setStatus(AppointmentStatus.NO_SHOW);
        restoreProductStock(appt);
        Appointment saved = appointmentRepository.save(appt);
        log.info("Appointment id={} marked NO_SHOW", saved.getId());
        return saved;
    }

    /**
     * Updates a SCHEDULED appointment in full (lines + stock management).
     * For non-SCHEDULED appointments only notes and payment info are updated.
     */
    public Appointment updateAppointment(Long id, AppointmentForm form) {
        Appointment existing = getById(id); // loads both collections

        Patient patient = patientRepository.findById(form.getPatientId())
                .orElseThrow(() -> new EntityNotFoundException("Patient not found"));
        Therapist therapist = therapistRepository.findById(form.getTherapistId())
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));

        PaymentMethod pm = null;
        if (form.getPaymentMethod() != null && !form.getPaymentMethod().isBlank()) {
            try { pm = PaymentMethod.valueOf(form.getPaymentMethod().trim()); }
            catch (IllegalArgumentException ignored) {}
        }

        existing.setPatient(patient);
        existing.setTherapist(therapist);
        existing.setAppointmentDateTime(form.getAppointmentDateTime());
        existing.setNotes(form.getNotes());
        existing.setPaymentMethod(pm);
        existing.setAmountPaid(form.getAmountPaid() != null ? form.getAmountPaid() : BigDecimal.ZERO);

        if (existing.getStatus() == AppointmentStatus.SCHEDULED) {
            List<AppointmentForm.ServiceLineForm> rawServices = form.getServiceLines().stream()
                    .filter(s -> s != null && s.getServiceId() != null)
                    .toList();
            if (rawServices.isEmpty()) {
                throw new IllegalArgumentException("At least one service must be selected.");
            }

            restoreProductStock(existing);  // restore before clearing lines
            existing.getServiceLines().clear();
            existing.getProductLines().clear();

            BigDecimal totalServiceAmount = BigDecimal.ZERO;
            for (AppointmentForm.ServiceLineForm slf : rawServices) {
                ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                        .orElseThrow(() -> new EntityNotFoundException("Service not found: " + slf.getServiceId()));
                int qty = Math.max(1, slf.getQuantity());
                BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));
                existing.getServiceLines().add(
                        AppointmentServiceLine.builder()
                                .appointment(existing)
                                .service(cs)
                                .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                                .priceAtTime(cs.getPrice())
                                .quantity(qty)
                                .build());
                totalServiceAmount = totalServiceAmount.add(lineTotal);
            }

            BigDecimal totalProductAmount = BigDecimal.ZERO;
            for (AppointmentForm.ProductLineForm plf : form.getProductLines()) {
                if (plf == null || plf.getProductId() == null) continue;
                int qty = Math.max(1, plf.getQuantity());
                Product product = productRepository.findById(plf.getProductId())
                        .orElseThrow(() -> new EntityNotFoundException("Product not found: " + plf.getProductId()));
                if (product.getStockQuantity() < qty) {
                    throw new IllegalArgumentException(
                            "Insufficient stock for '" + product.getName()
                            + "'. Available: " + product.getStockQuantity()
                            + ", requested: " + qty);
                }
                BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
                existing.getProductLines().add(
                        AppointmentProductLine.builder()
                                .appointment(existing)
                                .product(product)
                                .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                                .quantity(qty)
                                .priceAtTime(product.getPrice())
                                .lineTotal(lineTotal)
                                .build());
                product.setStockQuantity(product.getStockQuantity() - qty);
                log.info("Stock decremented for product id={} name='{}' by {} (remaining={})",
                        product.getId(), product.getName(), qty, product.getStockQuantity());
                totalProductAmount = totalProductAmount.add(lineTotal);
            }

            existing.setTotalServiceAmount(totalServiceAmount);
            existing.setTotalProductAmount(totalProductAmount);
            existing.setGrandTotal(totalServiceAmount.add(totalProductAmount));
        }

        Appointment saved = appointmentRepository.save(existing);
        log.info("Updated appointment id={}", saved.getId());
        return saved;
    }

    // ── Per-line therapist reassignment ──────────────────────────────────────
    // Allowed regardless of appointment status: only the line's therapist changes,
    // price/quantity/stock are untouched, so commission/revenue recalculates live
    // even for COMPLETED appointments.

    public AppointmentServiceLine reassignServiceLineTherapist(Long appointmentId, Long lineId, Long newTherapistId) {
        AppointmentServiceLine line = appointmentServiceLineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("Service line not found: " + lineId));
        if (!line.getAppointment().getId().equals(appointmentId)) {
            throw new IllegalArgumentException("Service line does not belong to this appointment.");
        }
        Therapist therapist = therapistRepository.findById(newTherapistId)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));
        line.setTherapist(therapist);
        AppointmentServiceLine saved = appointmentServiceLineRepository.save(line);
        log.info("Reassigned service line id={} (appointment id={}) to therapist '{}'",
                lineId, appointmentId, therapist.getFullName());
        return saved;
    }

    public AppointmentProductLine reassignProductLineTherapist(Long appointmentId, Long lineId, Long newTherapistId) {
        AppointmentProductLine line = appointmentProductLineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("Product line not found: " + lineId));
        if (!line.getAppointment().getId().equals(appointmentId)) {
            throw new IllegalArgumentException("Product line does not belong to this appointment.");
        }
        Therapist therapist = therapistRepository.findById(newTherapistId)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));
        line.setTherapist(therapist);
        AppointmentProductLine saved = appointmentProductLineRepository.save(line);
        log.info("Reassigned product line id={} (appointment id={}) to therapist '{}'",
                lineId, appointmentId, therapist.getFullName());
        return saved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resolves a line's therapist: the explicitly chosen one, or the appointment's main therapist as default. */
    private Therapist resolveLineTherapist(Long lineTherapistId, Therapist defaultTherapist) {
        if (lineTherapistId == null) return defaultTherapist;
        return therapistRepository.findById(lineTherapistId)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found: " + lineTherapistId));
    }

    private void restoreProductStock(Appointment appt) {
        for (AppointmentProductLine pl : appt.getProductLines()) {
            Product p = pl.getProduct();
            p.setStockQuantity(p.getStockQuantity() + pl.getQuantity());
        }
        // Product changes are flushed automatically within the same transaction
    }
}