package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
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
public class AppointmentService {

    private final AppointmentRepository        appointmentRepository;
    private final PatientRepository            patientRepository;
    private final TherapistRepository          therapistRepository;
    private final ClinicServiceRepository      clinicServiceRepository;
    private final ProductRepository            productRepository;

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
        LocalDateTime start = dateFrom != null ? dateFrom.atStartOfDay()      : null;
        LocalDateTime end   = dateTo   != null ? dateTo.atTime(LocalTime.MAX) : null;

        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.hasStatus(status))
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(start, end))
                .and(AppointmentSpec.patientNameContains(patientName));

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
                            .quantity(qty)
                            .priceAtTime(product.getPrice())
                            .lineTotal(lineTotal)
                            .build());

            // Decrement stock — treatment has been administered
            product.setStockQuantity(product.getStockQuantity() - qty);

            totalProductAmount = totalProductAmount.add(lineTotal);
        }

        // 6. Set aggregate totals
        appointment.setTotalServiceAmount(totalServiceAmount);
        appointment.setTotalProductAmount(totalProductAmount);
        appointment.setGrandTotal(totalServiceAmount.add(totalProductAmount));

        return appointmentRepository.save(appointment);
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
        return appointmentRepository.save(appt);
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
        return appointmentRepository.save(appt);
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
        return appointmentRepository.save(appt);
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
                                .quantity(qty)
                                .priceAtTime(product.getPrice())
                                .lineTotal(lineTotal)
                                .build());
                product.setStockQuantity(product.getStockQuantity() - qty);
                totalProductAmount = totalProductAmount.add(lineTotal);
            }

            existing.setTotalServiceAmount(totalServiceAmount);
            existing.setTotalProductAmount(totalProductAmount);
            existing.setGrandTotal(totalServiceAmount.add(totalProductAmount));
        }

        return appointmentRepository.save(existing);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void restoreProductStock(Appointment appt) {
        for (AppointmentProductLine pl : appt.getProductLines()) {
            Product p = pl.getProduct();
            p.setStockQuantity(p.getStockQuantity() + pl.getQuantity());
        }
        // Product changes are flushed automatically within the same transaction
    }
}