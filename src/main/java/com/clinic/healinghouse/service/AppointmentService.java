package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.dto.CalendarEventDTO;
import com.clinic.healinghouse.dto.RescheduleResponseDTO;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ComboRepository                  comboRepository;

    private static final Sort DATE_DESC =
            Sort.by(Sort.Direction.DESC, "appointmentDateTime");

    /**
     * Ceiling on how long a single appointment can occupy a therapist. Also what makes
     * findConflicts' ±1-day DB pre-filter window provably safe (Bug_Report_v2 #12): without a cap,
     * a candidate appointment starting more than a day before the requested window but running long
     * enough to still overlap it would be excluded by that pre-filter before the exact overlap check
     * ever runs. With every appointment capped at 24h, the latest a candidate can start and still
     * reach into the requested window is within that same ±1-day margin, so the pre-filter can never
     * miss a genuine overlap.
     */
    private static final int MAX_DURATION_MINUTES = 24 * 60;

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
        appointmentRepository.findWithCombosById(id);       // initialises combos in L1 cache
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

        List<TherapistConflictDTO> conflicts = new ArrayList<>();
        for (Long therapistId : therapistIds) {
            conflicts.addAll(findConflictsForTherapist(therapistId, start, end, excludeAppointmentId));
        }
        return conflicts;
    }

    /**
     * Checks whether a single therapist is already booked on another SCHEDULED/COMPLETED
     * appointment overlapping [start, end), excluding excludeAppointmentId. The single-therapist
     * building block behind both the whole-form check above (looped over every therapist on the
     * form) and per-line therapist reassignment (reassignServiceLineTherapist/reassignProductLineTherapist),
     * which only ever needs to check the one therapist being newly assigned.
     */
    @Transactional(readOnly = true)
    public List<TherapistConflictDTO> findConflictsForTherapist(Long therapistId, LocalDateTime start,
                                                                  LocalDateTime end, Long excludeAppointmentId) {
        // Widened bound (± 1 day) so appointments that straddle midnight are still caught;
        // exact overlap is verified below, this is just a cheap pre-filter for the query.
        LocalDateTime boundStart = start.toLocalDate().minusDays(1).atStartOfDay();
        LocalDateTime boundEnd   = end.toLocalDate().plusDays(1).atStartOfDay();

        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(AppointmentSpec.hasTherapistId(therapistId))
                .and(AppointmentSpec.betweenDates(boundStart, boundEnd));

        String therapistName = therapistRepository.findById(therapistId)
                .map(Therapist::getFullName)
                .orElse("Therapist #" + therapistId);

        List<TherapistConflictDTO> conflicts = new ArrayList<>();
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
        return conflicts;
    }

    /**
     * Latest appointment date for this therapist (main or line therapist), any status, if any.
     * Used by the Therapist Detail page to auto-widen its default "to" date filter so an
     * appointment dragged forward on the calendar — or one dragged forward and then cancelled —
     * doesn't silently fall outside the default month-to-date history/earnings view. Not scoped
     * to SCHEDULED only: once a dragged appointment is cancelled it must still count here, or the
     * widened range collapses back to "today" and the cancelled appointment disappears again.
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> findLatestAppointmentDate(Long therapistId) {
        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.hasTherapistId(therapistId));
        return appointmentRepository.findAll(spec).stream()
                .map(a -> a.getAppointmentDateTime().toLocalDate())
                .max(Comparator.naturalOrder());
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
                statusColor(appointment.getStatus()),
                appointment.getStatus().name());
    }

    // ── Reschedule (drag/resize on the therapist calendar) ──────────────────

    /**
     * Applies a drag (new appointmentDateTime) or resize (new durationMinutes) from the
     * calendar. Only these two fields change — patient/therapist/lines/discount/wallet are
     * untouched, so grandTotal and everything derived from it stay exactly as they were.
     * Re-runs the same conflict check as the form (main + line therapists), excluding this
     * appointment's own id, honoring forceSave the same way the form's "Save anyway" does.
     */
    public RescheduleResponseDTO rescheduleAppointment(Long id, LocalDateTime newStart, Integer newDuration, boolean forceSave) {
        Appointment appt = getById(id);
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Only SCHEDULED appointments can be rescheduled.");
        }
        if (newStart == null || newDuration == null || newDuration <= 0) {
            throw new IllegalArgumentException("A valid date/time and duration are required.");
        }
        validateDuration(newDuration);

        AppointmentForm probe = AppointmentForm.from(appt);
        probe.setAppointmentDateTime(newStart);
        probe.setDurationMinutes(newDuration);

        List<TherapistConflictDTO> conflicts = findConflicts(probe, id);
        if (!conflicts.isEmpty() && !forceSave) {
            return new RescheduleResponseDTO(false, "Scheduling conflict detected.", conflicts);
        }

        appt.setAppointmentDateTime(newStart);
        appt.setDurationMinutes(newDuration);
        appointmentRepository.save(appt);
        log.info("Appointment id={} rescheduled to start={} duration={}min", id, newStart, newDuration);
        return new RescheduleResponseDTO(true, "Rescheduled.", List.of());
    }

    /** Mirrors the status → color convention already used in appointments/list.html. */
    private String statusColor(AppointmentStatus status) {
        return switch (status) {
            case COMPLETED -> "#198754"; // bg-success  (green)
            case CANCELLED -> "#dc3545"; // bg-danger   (red)
            case NO_SHOW   -> "#ffc107"; // bg-warning  (yellow)
            case SCHEDULED -> "#0d6efd"; // bg-primary  (blue) — matches appointments/list.html's status badge
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

        validateDuration(form.getDurationMinutes());
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

        // 3b. Combo groups — one unsaved AppointmentCombo per selection, re-resolved from the live
        // Combo catalog entry (never a client-sent discount). Lines below attach to these by groupKey.
        Map<String, AppointmentCombo> comboByGroupKey = buildComboSelections(appointment, form.getComboSelections());

        // 4. Service lines — snapshot price at booking time
        BigDecimal totalServiceAmount = BigDecimal.ZERO;
        for (AppointmentForm.ServiceLineForm slf : rawServices) {
            ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Service not found: " + slf.getServiceId()));
            int qty = Math.max(1, slf.getQuantity());
            BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));
            AppointmentCombo lineCombo = slf.getComboGroupKey() != null ? comboByGroupKey.get(slf.getComboGroupKey()) : null;

            appointment.getServiceLines().add(
                    AppointmentServiceLine.builder()
                            .appointment(appointment)
                            .service(cs)
                            .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                            .priceAtTime(cs.getPrice())
                            .quantity(qty)
                            .appointmentCombo(lineCombo)
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
            AppointmentCombo lineCombo = plf.getComboGroupKey() != null ? comboByGroupKey.get(plf.getComboGroupKey()) : null;

            appointment.getProductLines().add(
                    AppointmentProductLine.builder()
                            .appointment(appointment)
                            .product(product)
                            .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                            .quantity(qty)
                            .priceAtTime(product.getPrice())
                            .lineTotal(lineTotal)
                            .appointmentCombo(lineCombo)
                            .build());

            // Stock is only decremented when the appointment is later marked COMPLETED (see
            // markAsCompleted) — this is just an availability check at booking time.
            totalProductAmount = totalProductAmount.add(lineTotal);
        }

        // 6. Set aggregate totals, then the two-phase discount: each combo's own discount over its
        // own lines first, then the whole-appointment discount layered on top of the result.
        appointment.setTotalServiceAmount(totalServiceAmount);
        appointment.setTotalProductAmount(totalProductAmount);
        for (AppointmentCombo ac : appointment.getCombos()) {
            applyComboDiscount(appointment, ac);
        }
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

    /** SCHEDULED → COMPLETED. Sets completedAt timestamp and decrements product stock (treatment actually administered now). */
    public Appointment markAsCompleted(Long id) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        appointmentRepository.findWithProductLinesById(id); // initialises productLines in L1 cache
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Only SCHEDULED appointments can be marked as completed.");
        }
        if (appt.getBalanceDue().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException(
                    "Cannot complete appointment: balance due of ₹" + appt.getBalanceDue() + " must be cleared first.");
        }
        for (AppointmentProductLine pl : appt.getProductLines()) {
            Product product = pl.getProduct();
            product.setStockQuantity(product.getStockQuantity() - pl.getQuantity());
            log.info("Stock decremented for product id={} name='{}' by {} (remaining={})",
                    product.getId(), product.getName(), pl.getQuantity(), product.getStockQuantity());
        }
        appt.setStatus(AppointmentStatus.COMPLETED);
        appt.setCompletedAt(LocalDateTime.now());
        Appointment saved = appointmentRepository.save(appt);
        log.info("Appointment id={} marked COMPLETED", saved.getId());
        return saved;
    }

    /** SCHEDULED → CANCELLED. Stores reason. Stock is never decremented before COMPLETED, so nothing to restore. */
    public Appointment cancelAppointment(Long id, String reason) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Only SCHEDULED appointments can be cancelled.");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setCancelReason(reason);
        reverseFullWalletIfAny(appt);
        Appointment saved = saveWithConflictCheck(appt);
        log.info("Appointment id={} marked CANCELLED reason='{}'", saved.getId(), reason);
        return saved;
    }

    /** SCHEDULED → NO_SHOW. Stock is never decremented before COMPLETED, so nothing to restore. */
    public Appointment markAsNoShow(Long id) {
        Appointment appt = appointmentRepository.findWithServiceLinesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found: " + id));
        if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Only SCHEDULED appointments can be marked as no-show.");
        }
        appt.setStatus(AppointmentStatus.NO_SHOW);
        reverseFullWalletIfAny(appt);
        Appointment saved = saveWithConflictCheck(appt);
        log.info("Appointment id={} marked NO_SHOW", saved.getId());
        return saved;
    }

    /**
     * Updates a SCHEDULED appointment in full (lines + stock management).
     * For non-SCHEDULED appointments only notes and payment info are updated.
     */
    public Appointment updateAppointment(Long id, AppointmentForm form) {
        Appointment existing = getById(id); // loads both collections

        // Once an appointment leaves SCHEDULED, only notes/payment-info stay editable — everything
        // else (who/when/lines/discount/wallet) is frozen, matching this method's own contract
        // ("For non-SCHEDULED appointments only notes and payment info are updated"). Previously this
        // was only enforced for the line-item rebuild below; patient/therapist/date/duration/wallet
        // were silently editable via this endpoint on any COMPLETED/CANCELLED/NO_SHOW appointment too.
        boolean editable = existing.getStatus() == AppointmentStatus.SCHEDULED;

        Patient patient = existing.getPatient();
        Therapist therapist = existing.getTherapist();

        if (editable) {
            Long originalPatientId = existing.getPatient().getId();
            patient = patientRepository.findById(form.getPatientId())
                    .orElseThrow(() -> new EntityNotFoundException("Patient not found"));
            therapist = therapistRepository.findById(form.getTherapistId())
                    .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));

            BigDecimal walletAlreadyApplied = existing.getWalletAmountApplied() != null
                    ? existing.getWalletAmountApplied() : BigDecimal.ZERO;
            if (!originalPatientId.equals(patient.getId()) && walletAlreadyApplied.signum() > 0) {
                throw new IllegalArgumentException(
                        "Cannot change the patient on this appointment while wallet funds (₹" + walletAlreadyApplied
                        + ") are applied to it. Remove the wallet amount first, then reassign the patient.");
            }

            validateDuration(form.getDurationMinutes());
            existing.setPatient(patient);
            existing.setTherapist(therapist);
            existing.setAppointmentDateTime(form.getAppointmentDateTime());
            existing.setDurationMinutes(form.getDurationMinutes() != null && form.getDurationMinutes() > 0
                    ? form.getDurationMinutes() : 60);
        }

        PaymentMethod pm = null;
        if (form.getPaymentMethod() != null && !form.getPaymentMethod().isBlank()) {
            try { pm = PaymentMethod.valueOf(form.getPaymentMethod().trim()); }
            catch (IllegalArgumentException ignored) {}
        }

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

        if (editable) {
            List<AppointmentForm.ServiceLineForm> rawServices = form.getServiceLines().stream()
                    .filter(s -> s != null && s.getServiceId() != null)
                    .toList();
            if (rawServices.isEmpty()) {
                throw new IllegalArgumentException("At least one service must be selected.");
            }

            existing.getServiceLines().clear();
            existing.getProductLines().clear();
            existing.getCombos().clear();

            Map<String, AppointmentCombo> comboByGroupKey = buildComboSelections(existing, form.getComboSelections());

            BigDecimal totalServiceAmount = BigDecimal.ZERO;
            for (AppointmentForm.ServiceLineForm slf : rawServices) {
                ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                        .orElseThrow(() -> new EntityNotFoundException("Service not found: " + slf.getServiceId()));
                int qty = Math.max(1, slf.getQuantity());
                BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));
                AppointmentCombo lineCombo = slf.getComboGroupKey() != null ? comboByGroupKey.get(slf.getComboGroupKey()) : null;
                existing.getServiceLines().add(
                        AppointmentServiceLine.builder()
                                .appointment(existing)
                                .service(cs)
                                .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                                .priceAtTime(cs.getPrice())
                                .quantity(qty)
                                .appointmentCombo(lineCombo)
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
                AppointmentCombo lineCombo = plf.getComboGroupKey() != null ? comboByGroupKey.get(plf.getComboGroupKey()) : null;
                existing.getProductLines().add(
                        AppointmentProductLine.builder()
                                .appointment(existing)
                                .product(product)
                                .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                                .quantity(qty)
                                .priceAtTime(product.getPrice())
                                .lineTotal(lineTotal)
                                .appointmentCombo(lineCombo)
                                .build());
                totalProductAmount = totalProductAmount.add(lineTotal);
            }

            existing.setTotalServiceAmount(totalServiceAmount);
            existing.setTotalProductAmount(totalProductAmount);
            for (AppointmentCombo ac : existing.getCombos()) {
                applyComboDiscount(existing, ac);
            }
            applyDiscount(existing, resolveDiscountType(form.getDiscountType()), form.getDiscountValue());
        }

        // Wallet balance applied — a target, not a delta: it's the total wallet-sourced amount this
        // appointment should now carry, silently capped at the (possibly just-shrunk) grandTotal. The
        // capping IS the auto-reversal trigger for a discount added/increased or a line removed: whenever
        // grandTotal drops below the previously-applied amount, the delta below comes out negative.
        // Gated by `editable`: once non-SCHEDULED, cancelAppointment/markAsNoShow have already reversed
        // any applied wallet amount via reverseFullWalletIfAny — this endpoint must not let staff
        // re-apply (or top up) wallet funds against an appointment no service is being rendered for.
        BigDecimal previousWalletApplied = existing.getWalletAmountApplied() != null
                ? existing.getWalletAmountApplied() : BigDecimal.ZERO;
        BigDecimal walletDelta = BigDecimal.ZERO;
        if (editable) {
            BigDecimal walletRequested = form.getWalletAmountApplied() != null
                    ? form.getWalletAmountApplied() : previousWalletApplied;
            if (walletRequested.signum() < 0) {
                throw new IllegalArgumentException("Wallet amount applied cannot be negative.");
            }
            walletRequested = walletRequested.min(existing.getGrandTotal());
            walletDelta = walletRequested.subtract(previousWalletApplied);

            existing.setAmountPaid(existing.getAmountPaid().subtract(previousWalletApplied).add(walletRequested));
            existing.setWalletAmountApplied(walletRequested);
        }

        if (existing.getAmountPaid().compareTo(existing.getGrandTotal()) > 0) {
            throw new IllegalArgumentException(
                    "Amount paid (₹" + existing.getAmountPaid()
                    + ") cannot exceed the grand total (₹" + existing.getGrandTotal() + ").");
        }

        Appointment saved = saveWithConflictCheck(existing);

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
    // even for COMPLETED appointments. Still subject to the same double-booking check as the
    // main create/update flow (warn, never hard-block) — see findConflictsForTherapist.

    /**
     * Reassigns a service line's therapist, unless the new therapist is already booked elsewhere
     * during this appointment's window and forceReassign wasn't set — in which case nothing is
     * persisted and the conflict(s) are returned for the caller to display (mirrors the
     * conflicts/forceSave pattern createAppointment/updateAppointment use). An empty list means
     * the reassignment was applied.
     */
    public List<TherapistConflictDTO> reassignServiceLineTherapist(Long appointmentId, Long lineId,
                                                                     Long newTherapistId, boolean forceReassign) {
        AppointmentServiceLine line = appointmentServiceLineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("Service line not found: " + lineId));
        if (!line.getAppointment().getId().equals(appointmentId)) {
            throw new IllegalArgumentException("Service line does not belong to this appointment.");
        }
        Therapist therapist = therapistRepository.findById(newTherapistId)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));

        Appointment appt = line.getAppointment();
        List<TherapistConflictDTO> conflicts = findConflictsForTherapist(
                newTherapistId, appt.getAppointmentDateTime(), appt.getEndDateTime(), appointmentId);
        if (!conflicts.isEmpty() && !forceReassign) {
            return conflicts;
        }

        line.setTherapist(therapist);
        appointmentServiceLineRepository.save(line);
        log.info("Reassigned service line id={} (appointment id={}) to therapist '{}'",
                lineId, appointmentId, therapist.getFullName());
        return List.of();
    }

    /** Product-line counterpart of reassignServiceLineTherapist — same conflict-check contract. */
    public List<TherapistConflictDTO> reassignProductLineTherapist(Long appointmentId, Long lineId,
                                                                     Long newTherapistId, boolean forceReassign) {
        AppointmentProductLine line = appointmentProductLineRepository.findById(lineId)
                .orElseThrow(() -> new EntityNotFoundException("Product line not found: " + lineId));
        if (!line.getAppointment().getId().equals(appointmentId)) {
            throw new IllegalArgumentException("Product line does not belong to this appointment.");
        }
        Therapist therapist = therapistRepository.findById(newTherapistId)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found"));

        Appointment appt = line.getAppointment();
        List<TherapistConflictDTO> conflicts = findConflictsForTherapist(
                newTherapistId, appt.getAppointmentDateTime(), appt.getEndDateTime(), appointmentId);
        if (!conflicts.isEmpty() && !forceReassign) {
            return conflicts;
        }

        line.setTherapist(therapist);
        appointmentProductLineRepository.save(line);
        log.info("Reassigned product line id={} (appointment id={}) to therapist '{}'",
                lineId, appointmentId, therapist.getFullName());
        return List.of();
    }

    // ── Combos ────────────────────────────────────────────────────────────────

    /**
     * Builds one unsaved AppointmentCombo per selection, keyed by the submission's groupKey, and
     * attaches each to the owning appointment. discountType/discountValue are re-resolved from the
     * live Combo catalog entry — never trusted from the client — per Combos_Requirements_v1.md §4.2.
     */
    private Map<String, AppointmentCombo> buildComboSelections(Appointment appointment,
                                                                List<AppointmentForm.ComboSelectionForm> selections) {
        Map<String, AppointmentCombo> comboByGroupKey = new LinkedHashMap<>();
        if (selections == null) return comboByGroupKey;
        for (AppointmentForm.ComboSelectionForm sel : selections) {
            if (sel == null || sel.getComboId() == null || sel.getGroupKey() == null || sel.getGroupKey().isBlank()) continue;
            Combo combo = comboRepository.findById(sel.getComboId())
                    .orElseThrow(() -> new EntityNotFoundException("Combo not found: " + sel.getComboId()));
            AppointmentCombo ac = AppointmentCombo.builder()
                    .appointment(appointment)
                    .combo(combo)
                    .comboNameSnapshot(combo.getName())
                    .discountType(combo.getDiscountType())
                    .discountValue(combo.getDiscountValue())
                    .discountAmount(BigDecimal.ZERO)
                    .originalSubtotalSnapshot(BigDecimal.ZERO)
                    .build();
            comboByGroupKey.put(sel.getGroupKey(), ac);
            appointment.getCombos().add(ac);
        }
        return comboByGroupKey;
    }

    /**
     * Phase 1 of the two-phase discount: resolves one combo's own discount against the raw
     * lineTotal of just that combo's lines, and distributes it across only those lines. Lines
     * outside any combo are untouched here (see distributeDiscount for the whole-appointment phase).
     */
    private void applyComboDiscount(Appointment appointment, AppointmentCombo ac) {
        List<DiscountableLine> lines = new ArrayList<>();
        appointment.getServiceLines().stream()
                .filter(sl -> sl.getAppointmentCombo() == ac)
                .forEach(sl -> lines.add(new DiscountableLine(sl.getLineTotal(), sl::setDiscountedLineTotal)));
        appointment.getProductLines().stream()
                .filter(pl -> pl.getAppointmentCombo() == ac)
                .forEach(pl -> lines.add(new DiscountableLine(pl.getLineTotal(), pl::setDiscountedLineTotal)));

        BigDecimal comboSubtotal = lines.stream()
                .map(DiscountableLine::lineRaw).reduce(BigDecimal.ZERO, BigDecimal::add);
        ac.setOriginalSubtotalSnapshot(comboSubtotal);

        DiscountType type = ac.getDiscountType();
        BigDecimal rawValue = ac.getDiscountValue();
        if (type == null || type == DiscountType.NONE || rawValue == null || rawValue.signum() <= 0 || lines.isEmpty()) {
            ac.setDiscountAmount(BigDecimal.ZERO);
            return; // lines keep discountedLineTotal == null from this phase — same "no discount" semantics
        }
        if (type == DiscountType.PERCENTAGE && rawValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Combo percentage discount cannot exceed 100%.");
        }
        BigDecimal resolved = type == DiscountType.PERCENTAGE
                ? comboSubtotal.multiply(rawValue).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : rawValue.setScale(2, RoundingMode.HALF_UP);
        resolved = resolved.min(comboSubtotal);
        ac.setDiscountAmount(resolved);
        distributeAmount(lines, comboSubtotal, resolved);
    }

    // ── Discount ──────────────────────────────────────────────────────────────

    /**
     * Phase 2 (whole-appointment discount): resolves the discount (type + raw value) against
     * the appointment's effective subtotal — which already nets out any combo discounts from
     * phase 1 — and distributes it proportionally across every line's *current* effective total,
     * so a manual discount layers on top of whatever combo discount a line already carries
     * rather than re-discounting from scratch. Commission-relevant fields (priceAtTime, quantity,
     * lineTotal, totalServiceAmount, totalProductAmount) are never touched here.
     */
    private void applyDiscount(Appointment appointment, DiscountType type, BigDecimal rawValue) {
        BigDecimal subtotal = computeEffectiveSubtotal(appointment);

        if (type == null || type == DiscountType.NONE || rawValue == null || rawValue.signum() <= 0) {
            appointment.setDiscountType(DiscountType.NONE);
            appointment.setDiscountValue(null);
            appointment.setDiscountAmount(BigDecimal.ZERO);
            // Only standalone lines reset here — combo lines keep their phase-1 discountedLineTotal.
            appointment.getServiceLines().forEach(sl -> { if (sl.getAppointmentCombo() == null) sl.setDiscountedLineTotal(null); });
            appointment.getProductLines().forEach(pl -> { if (pl.getAppointmentCombo() == null) pl.setDiscountedLineTotal(null); });
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

    /** Sum of every line's current effective total (post combo phase) — the phase-2 discount basis. */
    private BigDecimal computeEffectiveSubtotal(Appointment appointment) {
        BigDecimal total = BigDecimal.ZERO;
        for (AppointmentServiceLine sl : appointment.getServiceLines()) total = total.add(sl.getEffectiveLineTotal());
        for (AppointmentProductLine pl : appointment.getProductLines()) total = total.add(pl.getEffectiveLineTotal());
        return total;
    }

    /** Phase 2's line collector — basis is each line's current effective total, not its raw total. */
    private void distributeDiscount(Appointment appointment, BigDecimal subtotal, BigDecimal discountAmount) {
        List<DiscountableLine> lines = new ArrayList<>();
        appointment.getServiceLines().forEach(sl ->
                lines.add(new DiscountableLine(sl.getEffectiveLineTotal(), sl::setDiscountedLineTotal)));
        appointment.getProductLines().forEach(pl ->
                lines.add(new DiscountableLine(pl.getEffectiveLineTotal(), pl::setDiscountedLineTotal)));
        distributeAmount(lines, subtotal, discountAmount);
    }

    /**
     * Splits amount proportionally across the given lines, by each line's share of basisSubtotal.
     * Lines are processed smallest-basis first; the last (largest) line absorbs whatever rounding
     * remainder is left, so the per-line shares always sum exactly to amount. Shared by both discount
     * phases — only which lines and which basis value get passed in differs between them.
     */
    private void distributeAmount(List<DiscountableLine> lines, BigDecimal basisSubtotal, BigDecimal amount) {
        if (lines.isEmpty()) return;
        if (basisSubtotal.signum() <= 0) {
            // Nothing to proportion a discount against (e.g. every line in the basis is ₹0) —
            // leave every line at its raw total rather than dividing by zero.
            lines.forEach(l -> l.setter().accept(l.lineRaw()));
            return;
        }
        lines.sort(Comparator.comparing(DiscountableLine::lineRaw));

        BigDecimal[] shares = new BigDecimal[lines.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size() - 1; i++) {
            BigDecimal lineRaw = lines.get(i).lineRaw();
            BigDecimal share = amount.multiply(lineRaw)
                    .divide(basisSubtotal, 10, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP)
                    .min(lineRaw); // a line's own share can never rationally exceed its own raw total
            shares[i] = share;
            allocated = allocated.add(share);
        }

        // The last (largest) line absorbs whatever remainder is left so the total always sums exactly
        // to `amount` — but independently HALF_UP-rounding every earlier share can over-allocate by up
        // to ~0.005 each, so with enough lines and a small `amount` that remainder can go negative
        // (a "discount" that raises this line's price) or, in principle, exceed the line's own raw
        // total. Clamp it to [0, lineRaw] like every other line, then walk the leftover from clamping
        // backward through the earlier lines (nudging their shares up or down within their own [0,
        // lineRaw] bounds) until it's fully absorbed, so the exact-sum invariant still holds.
        int lastIdx = lines.size() - 1;
        BigDecimal lastRaw = lines.get(lastIdx).lineRaw();
        BigDecimal lastShare = amount.subtract(allocated);
        BigDecimal clampedLastShare = lastShare.max(BigDecimal.ZERO).min(lastRaw);
        shares[lastIdx] = clampedLastShare;
        BigDecimal leftover = lastShare.subtract(clampedLastShare);

        for (int i = lastIdx - 1; i >= 0 && leftover.signum() != 0; i--) {
            BigDecimal room = leftover.signum() > 0
                    ? lines.get(i).lineRaw().subtract(shares[i]) // room to raise this line's share
                    : shares[i];                                 // room to lower this line's share
            BigDecimal adjust = leftover.abs().min(room);
            if (adjust.signum() <= 0) continue;
            shares[i] = leftover.signum() > 0 ? shares[i].add(adjust) : shares[i].subtract(adjust);
            leftover = leftover.signum() > 0 ? leftover.subtract(adjust) : leftover.add(adjust);
        }

        for (int i = 0; i < lines.size(); i++) {
            DiscountableLine line = lines.get(i);
            line.setter().accept(line.lineRaw().subtract(shares[i]));
        }
    }

    private record DiscountableLine(BigDecimal lineRaw, Consumer<BigDecimal> setter) {}

    private void validateDuration(Integer durationMinutes) {
        if (durationMinutes != null && durationMinutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "Appointment duration cannot exceed " + (MAX_DURATION_MINUTES / 60) + " hours.");
        }
    }

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


    /**
     * Flushes the appointment save immediately (rather than deferring to transaction commit) so a
     * lost-update race against the same appointment row — e.g. a double-clicked status change or a
     * double-submitted edit, both of which may also carry a wallet reversal/debit alongside this save
     * — surfaces here as a clear, friendly message and rolls back the whole transaction (including
     * any wallet mutation already flushed earlier in the same method) instead of letting a second,
     * stale write silently re-apply its own wallet change on top of the first, already-committed one.
     * Mirrors WalletService.persistBalance's identical pattern for PatientWallet.
     */
    private Appointment saveWithConflictCheck(Appointment appt) {
        try {
            return appointmentRepository.saveAndFlush(appt);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(
                    "This appointment was just updated by someone else. Please refresh and try again.", ex);
        }
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