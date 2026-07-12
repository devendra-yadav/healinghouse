package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.dto.CalendarEventDTO;
import com.clinic.healinghouse.dto.TherapistConflictDTO;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
    private final WalletService                    walletService;

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

    /** Same as the patient-scoped overload, but paginated (used by the Appointments list, Patient Detail and Therapist Detail history tables). */
    @Transactional(readOnly = true)
    public Page<Appointment> findByFilters(AppointmentStatus status,
                                           Long therapistId,
                                           LocalDate dateFrom,
                                           LocalDate dateTo,
                                           String patientName,
                                           Long patientId,
                                           Pageable pageable) {
        LocalDateTime start = dateFrom != null ? dateFrom.atStartOfDay()      : null;
        LocalDateTime end   = dateTo   != null ? dateTo.atTime(LocalTime.MAX) : null;

        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.hasStatus(status))
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(start, end))
                .and(AppointmentSpec.patientNameOrPhoneContains(patientName))
                .and(AppointmentSpec.hasPatientId(patientId));

        return appointmentRepository.findAll(spec, pageable);
    }

    /**
     * Counts appointments matching the given filters that are also COMPLETED — used by the Therapist
     * Detail "Appointments" summary card, which needs a completed-count across the full filtered range,
     * not just the current page. AND-ing {@code status} with COMPLETED reproduces the pre-pagination
     * behaviour (filtering the completed-count within whatever status the user already selected).
     */
    @Transactional(readOnly = true)
    public long countCompleted(AppointmentStatus status,
                               Long therapistId,
                               LocalDate dateFrom,
                               LocalDate dateTo,
                               String patientName,
                               Long patientId) {
        LocalDateTime start = dateFrom != null ? dateFrom.atStartOfDay()      : null;
        LocalDateTime end   = dateTo   != null ? dateTo.atTime(LocalTime.MAX) : null;

        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.hasStatus(status))
                .and(AppointmentSpec.hasStatus(AppointmentStatus.COMPLETED))
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(start, end))
                .and(AppointmentSpec.patientNameOrPhoneContains(patientName))
                .and(AppointmentSpec.hasPatientId(patientId));

        return appointmentRepository.count(spec);
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

    // ── Conflict detection ───────────────────────────────────────────────────

    /**
     * Checks whether the therapist(s) on this form (main therapist + every line's therapist)
     * are already booked on another appointment overlapping the requested time window.
     * A therapist is "busy" for an appointment if they're the main therapist OR assigned to
     * any of its lines (mirrors AppointmentSpec.hasTherapistId). Only SCHEDULED/COMPLETED
     * appointments count — CANCELLED/NO_SHOW never conflict. When editing, pass the
     * appointment's own id so it doesn't flag a conflict against itself.
     */
    @Transactional(readOnly = true)
    public List<TherapistConflictDTO> findConflicts(AppointmentForm form, Long excludeAppointmentId) {
        if (form.getAppointmentDateTime() == null) return List.of();

        LocalDateTime start = form.getAppointmentDateTime();
        int duration = form.getDurationMinutes() != null && form.getDurationMinutes() > 0
                ? form.getDurationMinutes() : 60;
        LocalDateTime end = start.plusMinutes(duration);

        Set<Long> therapistIds = new LinkedHashSet<>();
        if (form.getTherapistId() != null) therapistIds.add(form.getTherapistId());
        form.getServiceLines().forEach(sl -> {
            if (sl != null && sl.getTherapistId() != null) therapistIds.add(sl.getTherapistId());
        });
        form.getProductLines().forEach(pl -> {
            if (pl != null && pl.getTherapistId() != null) therapistIds.add(pl.getTherapistId());
        });
        if (therapistIds.isEmpty()) return List.of();

        // Widened bound (± 1 day) so appointments that straddle midnight are still caught;
        // exact overlap is verified below, this is just a cheap pre-filter for the query.
        LocalDateTime boundStart = start.toLocalDate().minusDays(1).atStartOfDay();
        LocalDateTime boundEnd   = end.toLocalDate().plusDays(1).atStartOfDay();

        List<TherapistConflictDTO> conflicts = new ArrayList<>();
        for (Long therapistId : therapistIds) {
            Specification<Appointment> spec = Specification
                    .where(AppointmentSpec.withPatientAndTherapist())
                    .and(AppointmentSpec.hasTherapistId(therapistId))
                    .and(AppointmentSpec.betweenDates(boundStart, boundEnd));

            String therapistName = therapistRepository.findById(therapistId)
                    .map(Therapist::getFullName)
                    .orElse("Therapist #" + therapistId);

            for (Appointment candidate : appointmentRepository.findAll(spec)) {
                if (excludeAppointmentId != null && candidate.getId().equals(excludeAppointmentId)) continue;
                if (candidate.getStatus() == AppointmentStatus.CANCELLED
                        || candidate.getStatus() == AppointmentStatus.NO_SHOW) continue;

                LocalDateTime candidateStart = candidate.getAppointmentDateTime();
                LocalDateTime candidateEnd   = candidate.getEndDateTime();
                boolean overlaps = candidateStart.isBefore(end) && start.isBefore(candidateEnd);
                if (!overlaps) continue;

                conflicts.add(new TherapistConflictDTO(
                        therapistId, therapistName,
                        candidate.getId(), candidate.getPatient().getFullName(),
                        candidateStart, candidateEnd));
            }
        }
        return conflicts;
    }

    // ── Calendar feed ────────────────────────────────────────────────────────

    /**
     * Appointments for one therapist's calendar view, within the visible date range.
     * "This therapist" means main therapist OR any line therapist (mirrors hasTherapistId).
     * The query window is widened by a day on each side so appointments that straddle
     * the range boundary (e.g. started just before midnight) still show up.
     */
    @Transactional(readOnly = true)
    public List<CalendarEventDTO> findCalendarEvents(Long therapistId, LocalDateTime start, LocalDateTime end) {
        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(start.minusDays(1), end.plusDays(1)));

        return appointmentRepository.findAll(spec).stream()
                .map(a -> toCalendarEvent(a, therapistId))
                .toList();
    }

    private CalendarEventDTO toCalendarEvent(Appointment appointment, Long viewedTherapistId) {
        String title = appointment.getPatient().getFullName();
        if (!appointment.getTherapist().getId().equals(viewedTherapistId)) {
            title = title + " (with " + appointment.getTherapist().getFullName() + ")";
        }
        return new CalendarEventDTO(
                appointment.getId(),
                title,
                appointment.getAppointmentDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                appointment.getEndDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                statusColor(appointment.getStatus()));
    }

    /** Mirrors the status → color convention already used in appointments/list.html. */
    private String statusColor(AppointmentStatus status) {
        return switch (status) {
            case COMPLETED -> "#198754"; // bg-success
            case CANCELLED -> "#dc3545"; // bg-danger
            case NO_SHOW   -> "#ffc107"; // bg-warning
            case SCHEDULED -> "#40916c"; // clinic brand green
        };
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
                .durationMinutes(form.getDurationMinutes() != null && form.getDurationMinutes() > 0
                        ? form.getDurationMinutes() : 60)
                .notes(form.getNotes())
                .paymentMethod(paymentMethod)
                .amountPaid(form.getNewPaymentAmount() != null ? form.getNewPaymentAmount() : BigDecimal.ZERO)
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

        // 6. Set aggregate totals (and apply any discount)
        appointment.setTotalServiceAmount(totalServiceAmount);
        appointment.setTotalProductAmount(totalProductAmount);
        applyDiscount(appointment, resolveDiscountType(form.getDiscountType()), form.getDiscountValue());

        // 6b. Wallet balance applied — a payment source alongside cash/UPI/card, never a discount.
        // Silently capped at grandTotal (mirrors applyDiscount's resolved.min(subtotal)) rather than
        // rejected, so it composes cleanly with the exceeds-grandTotal guard below.
        BigDecimal walletRequested = form.getWalletAmountApplied() != null
                ? form.getWalletAmountApplied() : BigDecimal.ZERO;
        if (walletRequested.signum() < 0) {
            throw new IllegalArgumentException("Wallet amount applied cannot be negative.");
        }
        walletRequested = walletRequested.min(appointment.getGrandTotal());
        appointment.setWalletAmountApplied(walletRequested);
        appointment.setAmountPaid(appointment.getAmountPaid().add(walletRequested));

        if (appointment.getAmountPaid().compareTo(appointment.getGrandTotal()) > 0) {
            throw new IllegalArgumentException(
                    "Amount paid (₹" + appointment.getAmountPaid()
                    + ") cannot exceed the grand total (₹" + appointment.getGrandTotal() + ").");
        }

        Appointment saved = appointmentRepository.save(appointment);

        // Debited last so it's the only step that can still fail after every other validation
        // passed — @Transactional rolls the whole method back on an insufficient-balance failure,
        // no manual compensation needed.
        if (walletRequested.signum() > 0) {
            walletService.applyToAppointment(patient.getId(), saved.getId(), walletRequested);
        }

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
        if (appt.getBalanceDue().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException(
                    "Cannot complete appointment: balance due of ₹" + appt.getBalanceDue() + " must be cleared first.");
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
        reverseFullWalletIfAny(appt);
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
        reverseFullWalletIfAny(appt);
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
        existing.setDurationMinutes(form.getDurationMinutes() != null && form.getDurationMinutes() > 0
                ? form.getDurationMinutes() : 60);
        existing.setNotes(form.getNotes());
        existing.setPaymentMethod(pm);

        // Amount paid is cumulative: the "prepaid" base (existing total, or a corrected value if the
        // pencil was used) plus whatever new payment is being entered in this submission.
        BigDecimal prepaidBase = form.getPrepaidCorrection() != null
                ? form.getPrepaidCorrection() : existing.getAmountPaid();
        BigDecimal newPayment = form.getNewPaymentAmount() != null
                ? form.getNewPaymentAmount() : BigDecimal.ZERO;
        if (prepaidBase.signum() < 0 || newPayment.signum() < 0) {
            throw new IllegalArgumentException("Amount paid cannot be negative.");
        }
        existing.setAmountPaid(prepaidBase.add(newPayment));

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
            applyDiscount(existing, resolveDiscountType(form.getDiscountType()), form.getDiscountValue());
        }

        // Wallet balance applied — a target, not a delta: it's the total wallet-sourced amount this
        // appointment should now carry, silently capped at the (possibly just-shrunk) grandTotal. The
        // capping IS the auto-reversal trigger for a discount added/increased or a line removed: whenever
        // grandTotal drops below the previously-applied amount, the delta below comes out negative.
        BigDecimal previousWalletApplied = existing.getWalletAmountApplied() != null
                ? existing.getWalletAmountApplied() : BigDecimal.ZERO;
        BigDecimal walletRequested = form.getWalletAmountApplied() != null
                ? form.getWalletAmountApplied() : previousWalletApplied;
        if (walletRequested.signum() < 0) {
            throw new IllegalArgumentException("Wallet amount applied cannot be negative.");
        }
        walletRequested = walletRequested.min(existing.getGrandTotal());
        BigDecimal walletDelta = walletRequested.subtract(previousWalletApplied);

        existing.setAmountPaid(existing.getAmountPaid().subtract(previousWalletApplied).add(walletRequested));
        existing.setWalletAmountApplied(walletRequested);

        if (existing.getAmountPaid().compareTo(existing.getGrandTotal()) > 0) {
            throw new IllegalArgumentException(
                    "Amount paid (₹" + existing.getAmountPaid()
                    + ") cannot exceed the grand total (₹" + existing.getGrandTotal() + ").");
        }

        Appointment saved = appointmentRepository.save(existing);

        if (walletDelta.signum() > 0) {
            walletService.applyToAppointment(existing.getPatient().getId(), saved.getId(), walletDelta);
        } else if (walletDelta.signum() < 0) {
            walletService.reverseForAppointment(existing.getPatient().getId(), saved.getId(), walletDelta.abs());
        }

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

    // ── Discount ──────────────────────────────────────────────────────────────

    /**
     * Resolves the discount (type + raw value) against the appointment's current
     * totalServiceAmount/totalProductAmount, distributes it proportionally across
     * every service/product line as discountedLineTotal, and sets grandTotal to the
     * net (post-discount) amount. Commission-relevant fields (priceAtTime, quantity,
     * lineTotal, totalServiceAmount, totalProductAmount) are never touched here.
     */
    private void applyDiscount(Appointment appointment, DiscountType type, BigDecimal rawValue) {
        BigDecimal subtotal = appointment.getTotalServiceAmount().add(appointment.getTotalProductAmount());

        if (type == null || type == DiscountType.NONE || rawValue == null || rawValue.signum() <= 0) {
            appointment.setDiscountType(DiscountType.NONE);
            appointment.setDiscountValue(null);
            appointment.setDiscountAmount(BigDecimal.ZERO);
            appointment.getServiceLines().forEach(sl -> sl.setDiscountedLineTotal(null));
            appointment.getProductLines().forEach(pl -> pl.setDiscountedLineTotal(null));
            appointment.setGrandTotal(subtotal);
            return;
        }

        if (type == DiscountType.PERCENTAGE && rawValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100%.");
        }

        BigDecimal resolved = type == DiscountType.PERCENTAGE
                ? subtotal.multiply(rawValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : rawValue.setScale(2, RoundingMode.HALF_UP);
        resolved = resolved.min(subtotal); // never exceed subtotal

        appointment.setDiscountType(type);
        appointment.setDiscountValue(rawValue);
        appointment.setDiscountAmount(resolved);
        distributeDiscount(appointment, subtotal, resolved);
        appointment.setGrandTotal(subtotal.subtract(resolved));
    }

    /**
     * Splits discountAmount proportionally across every service+product line, by each
     * line's share of subtotal. Lines are processed smallest-raw-total first; the last
     * (largest) line absorbs whatever rounding remainder is left, so the per-line shares
     * always sum exactly to discountAmount and the largest line is safest to absorb it.
     */
    private void distributeDiscount(Appointment appointment, BigDecimal subtotal, BigDecimal discountAmount) {
        List<DiscountableLine> lines = new ArrayList<>();
        appointment.getServiceLines().forEach(sl ->
                lines.add(new DiscountableLine(sl.getLineTotal(), sl::setDiscountedLineTotal)));
        appointment.getProductLines().forEach(pl ->
                lines.add(new DiscountableLine(pl.getLineTotal(), pl::setDiscountedLineTotal)));

        if (lines.isEmpty()) return;
        lines.sort(Comparator.comparing(DiscountableLine::lineRaw));

        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            DiscountableLine line = lines.get(i);
            BigDecimal share;
            if (i == lines.size() - 1) {
                share = discountAmount.subtract(allocated);
            } else {
                share = discountAmount.multiply(line.lineRaw())
                        .divide(subtotal, 10, RoundingMode.HALF_UP)
                        .setScale(2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            line.setter().accept(line.lineRaw().subtract(share));
        }
    }

    private record DiscountableLine(BigDecimal lineRaw, Consumer<BigDecimal> setter) {}

    private DiscountType resolveDiscountType(String raw) {
        if (raw == null || raw.isBlank()) return DiscountType.NONE;
        try {
            return DiscountType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return DiscountType.NONE;
        }
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

    /** Credits back any wallet-sourced payment on an appointment that's being cancelled/no-showed. */
    private void reverseFullWalletIfAny(Appointment appt) {
        BigDecimal applied = appt.getWalletAmountApplied();
        if (applied != null && applied.signum() > 0) {
            walletService.reverseForAppointment(appt.getPatient().getId(), appt.getId(), applied);
            appt.setAmountPaid(appt.getAmountPaid().subtract(applied).max(BigDecimal.ZERO));
            appt.setWalletAmountApplied(BigDecimal.ZERO);
        }
    }
}