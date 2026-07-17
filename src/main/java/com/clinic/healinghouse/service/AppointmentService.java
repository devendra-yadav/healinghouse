package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.AppointmentForm;
import com.clinic.healinghouse.dto.CalendarEventDTO;
import com.clinic.healinghouse.dto.RescheduleResponseDTO;
import com.clinic.healinghouse.dto.TherapistConflictDTO;
import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.*;
import com.clinic.healinghouse.util.ProportionalAllocator;
import com.clinic.healinghouse.util.TherapistColorUtil;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final PackageService                   packageService;
    private final ComboRepository                  comboRepository;
    private final HealingHouseProperties           properties;

    private static final Sort DATE_DESC =
            Sort.by(Sort.Direction.DESC, "appointmentDateTime");

    /** findConflictsForTherapist's DB pre-filter window is hardcoded to ±1 day regardless of this
     * setting — see the check below and the comment at that pre-filter. */
    private static final int PRE_FILTER_WINDOW_MINUTES = 24 * 60;

    /**
     * Ceiling on how long a single appointment can occupy a therapist, sourced from
     * {@code healinghouse.appointment.max-duration-minutes}. Also what makes findConflicts' ±1-day
     * DB pre-filter window provably safe (Bug_Report_v2 #12): without a cap, a candidate appointment
     * starting more than a day before the requested window but running long enough to still overlap
     * it would be excluded by that pre-filter before the exact overlap check ever runs. With every
     * appointment capped at 24h, the latest a candidate can start and still reach into the requested
     * window is within that same ±1-day margin, so the pre-filter can never miss a genuine overlap.
     * {@link #validateMaxDurationAgainstConflictPreFilter()} fails startup if this is misconfigured
     * above the pre-filter's fixed 24h margin, since raising it silently would reopen that gap.
     */
    @PostConstruct
    void validateMaxDurationAgainstConflictPreFilter() {
        int configured = properties.getAppointment().getMaxDurationMinutes();
        if (configured > PRE_FILTER_WINDOW_MINUTES) {
            throw new IllegalStateException(
                    "healinghouse.appointment.max-duration-minutes (" + configured
                    + ") cannot exceed " + PRE_FILTER_WINDOW_MINUTES
                    + " — findConflictsForTherapist's DB pre-filter window is hardcoded to ±1 day and "
                    + "would silently miss double-booking conflicts for appointments longer than that.");
        }
    }

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
     * True if {@code therapistId} is the main therapist OR performed/sold any service/product line
     * on this appointment — the same "busy"/"own" definition {@link AppointmentSpec#hasTherapistId}
     * uses for list queries, but evaluated in-memory against an already-loaded entity for the
     * detail/action endpoints that only have one appointment in hand, not a query root
     * (requirements/Security_RBAC_Requirements_v1.md §7, §12 Phase C).
     */
    public boolean involvesTherapist(Appointment appt, Long therapistId) {
        if (therapistId == null) return false;
        if (appt.getTherapist() != null && therapistId.equals(appt.getTherapist().getId())) return true;
        boolean onServiceLine = appt.getServiceLines().stream()
                .anyMatch(sl -> sl.getTherapist() != null && therapistId.equals(sl.getTherapist().getId()));
        if (onServiceLine) return true;
        return appt.getProductLines().stream()
                .anyMatch(pl -> pl.getTherapist() != null && therapistId.equals(pl.getTherapist().getId()));
    }

    @Transactional(readOnly = true)
    public boolean involvesTherapist(Long appointmentId, Long therapistId) {
        return involvesTherapist(getById(appointmentId), therapistId);
    }

    /** Used to scope a THERAPIST-role user's visibility of a patient to only those tied to one of
     *  their own appointments (main or reassigned line) — no dedicated query needed, this reuses
     *  the same patient+therapist filtered lookup already backing the Patient/Therapist detail
     *  history tables. */
    @Transactional(readOnly = true)
    public boolean hasAnyAppointmentForPatientAndTherapist(Long patientId, Long therapistId) {
        if (therapistId == null) return false;
        return findByFilters(null, therapistId, null, null, null, patientId, PageRequest.of(0, 1)).hasContent();
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
                .map(a -> toCalendarEvent(a, therapistId, statusColor(a.getStatus())))
                .toList();
    }

    /**
     * Appointments overlaid across every selected therapist, for the all-therapists calendar
     * (GET /calendar). Unlike the single-therapist feed, an appointment involving more than one
     * *selected* therapist (main + a reassigned line) renders as one event per involved therapist
     * — see §5.2 of All_Therapists_Calendar_Requirements_v1.md — colored per therapist rather than
     * per status, since therapist identity is what the color now conveys.
     */
    @Transactional(readOnly = true)
    public List<CalendarEventDTO> findCalendarEventsForTherapists(List<Long> therapistIds, LocalDateTime start, LocalDateTime end) {
        Specification<Appointment> spec = Specification
                .where(AppointmentSpec.withPatientAndTherapist())
                .and(Specification.anyOf(therapistIds.stream().map(AppointmentSpec::hasTherapistId).toList()))
                .and(AppointmentSpec.betweenDates(start.minusDays(1), end.plusDays(1)));

        List<Appointment> appointments = appointmentRepository.findAll(spec);

        return appointments.stream()
                .flatMap(a -> therapistIds.stream()
                        .filter(tid -> isTherapistInvolved(a, tid))
                        .map(tid -> toCalendarEvent(a, tid, TherapistColorUtil.colorFor(tid))))
                .toList();
    }

    private boolean isTherapistInvolved(Appointment appointment, Long therapistId) {
        if (appointment.getTherapist().getId().equals(therapistId)) return true;
        boolean onServiceLine = appointment.getServiceLines().stream()
                .anyMatch(sl -> sl.getTherapist().getId().equals(therapistId));
        if (onServiceLine) return true;
        return appointment.getProductLines().stream()
                .anyMatch(pl -> pl.getTherapist().getId().equals(therapistId));
    }

    private CalendarEventDTO toCalendarEvent(Appointment appointment, Long viewedTherapistId, String color) {
        String title = appointment.getPatient().getFullName();
        if (!appointment.getTherapist().getId().equals(viewedTherapistId)) {
            title = title + " (with " + appointment.getTherapist().getFullName() + ")";
        }
        return new CalendarEventDTO(
                appointment.getId(),
                title,
                appointment.getAppointmentDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                appointment.getEndDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                color,
                appointment.getStatus().name(),
                viewedTherapistId);
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
        List<PendingPackageConsumption> pendingPackageConsumptions = new ArrayList<>();
        BigDecimal totalServiceAmount = BigDecimal.ZERO;
        for (AppointmentForm.ServiceLineForm slf : rawServices) {
            ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Service not found: " + slf.getServiceId()));
            int qty = Math.max(1, slf.getQuantity());
            BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));
            AppointmentCombo lineCombo = slf.getComboGroupKey() != null ? comboByGroupKey.get(slf.getComboGroupKey()) : null;

            PatientPackageServiceItem packageItem = null;
            if (slf.getPackageItemId() != null) {
                packageItem = packageService.resolveServiceItemForConsumption(slf.getPackageItemId(), patient.getId());
                pendingPackageConsumptions.add(new PendingPackageConsumption(packageItem.getId(), null, lineTotal));
            }

            appointment.getServiceLines().add(
                    AppointmentServiceLine.builder()
                            .appointment(appointment)
                            .service(cs)
                            .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                            .priceAtTime(cs.getPrice())
                            .quantity(qty)
                            .appointmentCombo(lineCombo)
                            .packageServiceItem(packageItem)
                            .build());

            totalServiceAmount = totalServiceAmount.add(lineTotal);
        }

        // 5. Product lines — validate aggregate stock demand (across all lines for the same product
        // in this submission, e.g. a standalone line plus a combo line), snapshot price.
        validateAggregateStockDemand(form.getProductLines());
        BigDecimal totalProductAmount = BigDecimal.ZERO;
        for (AppointmentForm.ProductLineForm plf : form.getProductLines()) {
            if (plf == null || plf.getProductId() == null) continue;
            int qty = Math.max(1, plf.getQuantity());

            Product product = productRepository.findById(plf.getProductId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Product not found: " + plf.getProductId()));

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
            AppointmentCombo lineCombo = plf.getComboGroupKey() != null ? comboByGroupKey.get(plf.getComboGroupKey()) : null;

            PatientPackageProductItem packageItem = null;
            if (plf.getPackageItemId() != null) {
                packageItem = packageService.resolveProductItemForConsumption(plf.getPackageItemId(), patient.getId());
                pendingPackageConsumptions.add(new PendingPackageConsumption(null, packageItem.getId(), lineTotal));
            }

            appointment.getProductLines().add(
                    AppointmentProductLine.builder()
                            .appointment(appointment)
                            .product(product)
                            .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                            .quantity(qty)
                            .priceAtTime(product.getPrice())
                            .lineTotal(lineTotal)
                            .appointmentCombo(lineCombo)
                            .packageProductItem(packageItem)
                            .build());

            // Stock is only decremented when the appointment is later marked COMPLETED (see
            // markAsCompleted) — this is just an availability check at booking time.
            totalProductAmount = totalProductAmount.add(lineTotal);
        }

        // 6. Set aggregate totals, then the two-phase discount: each combo's own discount over its
        // own lines first, then the whole-appointment discount layered on top of the result.
        appointment.setTotalServiceAmount(totalServiceAmount);
        appointment.setTotalProductAmount(totalProductAmount);
        removeOrphanCombos(appointment);
        for (AppointmentCombo ac : appointment.getCombos()) {
            applyComboDiscount(appointment, ac);
        }
        applyDiscount(appointment, resolveDiscountType(form.getDiscountType()), form.getDiscountValue());

        // 6a. Package-covered lines — a payment source alongside cash/UPI/card/wallet, never a
        // discount; always exactly the sum of covered lines' own (undiscounted) values, since a
        // package session covers a line's full value (no partial coverage). Actual consumption
        // (sessionsUsed++, ledger write) is deferred until after save (see below), mirroring the
        // wallet debit-last ordering so a mid-transaction failure rolls back cleanly.
        BigDecimal packageAmountApplied = pendingPackageConsumptions.stream()
                .map(PendingPackageConsumption::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        appointment.setPackageAmountApplied(packageAmountApplied);
        appointment.setAmountPaid(appointment.getAmountPaid().add(packageAmountApplied));

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
            String symbol = properties.getCurrency().getSymbol();
            throw new IllegalArgumentException(
                    "Amount paid (" + symbol + appointment.getAmountPaid()
                    + ") cannot exceed the grand total (" + symbol + appointment.getGrandTotal() + ").");
        }

        Appointment saved = appointmentRepository.save(appointment);

        // Debited/consumed last so they're the only steps that can still fail after every other
        // validation passed — @Transactional rolls the whole method back on an insufficient-balance
        // or insufficient-sessions failure, no manual compensation needed.
        for (PendingPackageConsumption pc : pendingPackageConsumptions) {
            if (pc.serviceItemId() != null) {
                packageService.consumeServiceItem(pc.serviceItemId(), saved.getId(), pc.amount());
            } else {
                packageService.consumeProductItem(pc.productItemId(), saved.getId(), pc.amount());
            }
        }
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
                    "Cannot complete appointment: balance due of " + properties.getCurrency().getSymbol()
                    + appt.getBalanceDue() + " must be cleared first.");
        }

        // Stock is only checked at booking time (an availability check, not a reservation) — it can
        // have shrunk since then via another appointment completing first, or via this same
        // appointment carrying the same product on more than one line. Re-validate live availability
        // against aggregate demand right before it's actually consumed.
        Map<Long, Integer> demandByProductId = new LinkedHashMap<>();
        for (AppointmentProductLine pl : appt.getProductLines()) {
            demandByProductId.merge(pl.getProduct().getId(), pl.getQuantity(), Integer::sum);
        }
        for (AppointmentProductLine pl : appt.getProductLines()) {
            Product product = pl.getProduct();
            int demand = demandByProductId.getOrDefault(product.getId(), 0);
            if (product.getStockQuantity() < demand) {
                throw new IllegalArgumentException(
                        "Insufficient stock for '" + product.getName()
                        + "'. Available: " + product.getStockQuantity()
                        + ", requested: " + demand);
            }
        }

        for (AppointmentProductLine pl : appt.getProductLines()) {
            Product product = pl.getProduct();
            product.setStockQuantity(product.getStockQuantity() - pl.getQuantity());
            log.info("Stock decremented for product id={} name='{}' by {} (remaining={})",
                    product.getId(), product.getName(), pl.getQuantity(), product.getStockQuantity());
        }
        appt.setStatus(AppointmentStatus.COMPLETED);
        appt.setCompletedAt(LocalDateTime.now());
        Appointment saved = saveWithConflictCheck(appt);
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
        reverseAllPackageConsumption(appt);
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
        reverseAllPackageConsumption(appt);
        Appointment saved = saveWithConflictCheck(appt);
        log.info("Appointment id={} marked NO_SHOW", saved.getId());
        return saved;
    }

    /**
     * Credits back every package-covered line on an appointment being cancelled/no-showed —
     * mirrors reverseFullWalletIfAny. Unlike the update-flow reconciliation, every currently-
     * attached line is being reversed (not diffed against a resubmission), so no multiset math
     * is needed — one reverse call per line.
     */
    private void reverseAllPackageConsumption(Appointment appt) {
        for (AppointmentServiceLine sl : appt.getServiceLines()) {
            if (sl.getPackageServiceItem() != null) {
                packageService.reverseServiceItem(sl.getPackageServiceItem().getId(), appt.getId());
            }
        }
        for (AppointmentProductLine pl : appt.getProductLines()) {
            if (pl.getPackageProductItem() != null) {
                packageService.reverseProductItem(pl.getPackageProductItem().getId(), appt.getId());
            }
        }
        BigDecimal applied = appt.getPackageAmountApplied();
        if (applied != null && applied.signum() > 0) {
            appt.setAmountPaid(appt.getAmountPaid().subtract(applied).max(BigDecimal.ZERO));
            appt.setPackageAmountApplied(BigDecimal.ZERO);
        }
    }

    /**
     * Updates a SCHEDULED appointment in full (lines + stock management).
     * For non-SCHEDULED appointments only notes and payment info are updated.
     */
    public Appointment updateAppointment(Long id, AppointmentForm form) {
        Appointment existing = getById(id); // loads both collections

        // Reject a stale form: the edit page baked amountPaid/walletAmountApplied into hidden fields at
        // load time, and the client computes prepaidCorrection/walletAmountApplied as targets built on
        // top of that snapshot. If either has moved since (another staff member recorded a payment or
        // changed the wallet amount in the meantime), trusting the client's target would silently erase
        // or misapply that other change even though this appointment's @Version hasn't conflicted (each
        // request loads fresh, non-overlapping data — no version check would ever catch this).
        if (form.getExistingAmountPaidBaseline() != null
                && form.getExistingAmountPaidBaseline().compareTo(nz(existing.getAmountPaid())) != 0) {
            throw new IllegalStateException(
                    "This appointment's payment info was updated by someone else since you opened this form. "
                    + "Please refresh and try again.");
        }
        if (form.getExistingWalletAppliedBaseline() != null
                && form.getExistingWalletAppliedBaseline().compareTo(nz(existing.getWalletAmountApplied())) != 0) {
            throw new IllegalStateException(
                    "This appointment's wallet amount was updated by someone else since you opened this form. "
                    + "Please refresh and try again.");
        }

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
                        "Cannot change the patient on this appointment while wallet funds ("
                        + properties.getCurrency().getSymbol() + walletAlreadyApplied
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

        // Package-covered-line reconciliation state for the clear-and-rebuild below — see
        // reconcilePackageDelta's javadoc for why this must be a multiset (occurrence-count) diff
        // rather than a set diff: the same PatientPackageServiceItem/ProductItem can legitimately
        // back more than one line in this appointment (staff clicking "Add" twice for a service
        // with 2+ pooled sessions). Populated only when editable; both empty otherwise, which is a
        // correct no-op (a non-editable appointment's package lines are untouched here — they were
        // already reversed by reverseAllPackageConsumption if this appointment was just cancelled/
        // no-showed, or should simply stay consumed if it's COMPLETED).
        Map<Long, Integer> oldServiceItemCounts = Map.of();
        Map<Long, Integer> oldProductItemCounts = Map.of();
        Map<Long, Integer> newServiceItemCounts = Map.of();
        Map<Long, Integer> newProductItemCounts = Map.of();
        Map<Long, BigDecimal> serviceItemUnitAmounts = Map.of();
        Map<Long, BigDecimal> productItemUnitAmounts = Map.of();

        if (editable) {
            List<AppointmentForm.ServiceLineForm> rawServices = form.getServiceLines().stream()
                    .filter(s -> s != null && s.getServiceId() != null)
                    .toList();
            if (rawServices.isEmpty()) {
                throw new IllegalArgumentException("At least one service must be selected.");
            }

            oldServiceItemCounts = tallyServicePackageItemCounts(existing.getServiceLines());
            oldProductItemCounts = tallyProductPackageItemCounts(existing.getProductLines());

            existing.getServiceLines().clear();
            existing.getProductLines().clear();
            existing.getCombos().clear();

            Map<String, AppointmentCombo> comboByGroupKey = buildComboSelections(existing, form.getComboSelections());

            Map<Long, Integer> newSvcCounts = new LinkedHashMap<>();
            Map<Long, BigDecimal> svcUnitAmounts = new LinkedHashMap<>();
            BigDecimal totalServiceAmount = BigDecimal.ZERO;
            BigDecimal packageTotal = BigDecimal.ZERO;
            for (AppointmentForm.ServiceLineForm slf : rawServices) {
                ClinicService cs = clinicServiceRepository.findById(slf.getServiceId())
                        .orElseThrow(() -> new EntityNotFoundException("Service not found: " + slf.getServiceId()));
                int qty = Math.max(1, slf.getQuantity());
                BigDecimal lineTotal = cs.getPrice().multiply(BigDecimal.valueOf(qty));
                AppointmentCombo lineCombo = slf.getComboGroupKey() != null ? comboByGroupKey.get(slf.getComboGroupKey()) : null;

                PatientPackageServiceItem packageItem = null;
                if (slf.getPackageItemId() != null) {
                    packageItem = packageService.resolveServiceItemForConsumption(slf.getPackageItemId(), patient.getId());
                    newSvcCounts.merge(packageItem.getId(), 1, Integer::sum);
                    svcUnitAmounts.put(packageItem.getId(), lineTotal);
                    packageTotal = packageTotal.add(lineTotal);
                }

                existing.getServiceLines().add(
                        AppointmentServiceLine.builder()
                                .appointment(existing)
                                .service(cs)
                                .therapist(resolveLineTherapist(slf.getTherapistId(), therapist))
                                .priceAtTime(cs.getPrice())
                                .quantity(qty)
                                .appointmentCombo(lineCombo)
                                .packageServiceItem(packageItem)
                                .build());
                totalServiceAmount = totalServiceAmount.add(lineTotal);
            }
            newServiceItemCounts = newSvcCounts;
            serviceItemUnitAmounts = svcUnitAmounts;

            validateAggregateStockDemand(form.getProductLines());
            Map<Long, Integer> newPrdCounts = new LinkedHashMap<>();
            Map<Long, BigDecimal> prdUnitAmounts = new LinkedHashMap<>();
            BigDecimal totalProductAmount = BigDecimal.ZERO;
            for (AppointmentForm.ProductLineForm plf : form.getProductLines()) {
                if (plf == null || plf.getProductId() == null) continue;
                int qty = Math.max(1, plf.getQuantity());
                Product product = productRepository.findById(plf.getProductId())
                        .orElseThrow(() -> new EntityNotFoundException("Product not found: " + plf.getProductId()));
                BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(qty));
                AppointmentCombo lineCombo = plf.getComboGroupKey() != null ? comboByGroupKey.get(plf.getComboGroupKey()) : null;

                PatientPackageProductItem packageItem = null;
                if (plf.getPackageItemId() != null) {
                    packageItem = packageService.resolveProductItemForConsumption(plf.getPackageItemId(), patient.getId());
                    newPrdCounts.merge(packageItem.getId(), 1, Integer::sum);
                    prdUnitAmounts.put(packageItem.getId(), lineTotal);
                    packageTotal = packageTotal.add(lineTotal);
                }

                existing.getProductLines().add(
                        AppointmentProductLine.builder()
                                .appointment(existing)
                                .product(product)
                                .therapist(resolveLineTherapist(plf.getTherapistId(), therapist))
                                .quantity(qty)
                                .priceAtTime(product.getPrice())
                                .lineTotal(lineTotal)
                                .appointmentCombo(lineCombo)
                                .packageProductItem(packageItem)
                                .build());
                totalProductAmount = totalProductAmount.add(lineTotal);
            }
            newProductItemCounts = newPrdCounts;
            productItemUnitAmounts = prdUnitAmounts;

            existing.setTotalServiceAmount(totalServiceAmount);
            existing.setTotalProductAmount(totalProductAmount);
            removeOrphanCombos(existing);
            for (AppointmentCombo ac : existing.getCombos()) {
                applyComboDiscount(existing, ac);
            }
            applyDiscount(existing, resolveDiscountType(form.getDiscountType()), form.getDiscountValue());

            // Package-covered lines — always exactly the sum of covered lines' own values, recomputed
            // fresh on every rebuild (no user-entered target, unlike wallet's walletAmountApplied).
            BigDecimal previousPackageApplied = existing.getPackageAmountApplied() != null
                    ? existing.getPackageAmountApplied() : BigDecimal.ZERO;
            existing.setAmountPaid(existing.getAmountPaid().subtract(previousPackageApplied).add(packageTotal));
            existing.setPackageAmountApplied(packageTotal);
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
            String symbol = properties.getCurrency().getSymbol();
            throw new IllegalArgumentException(
                    "Amount paid (" + symbol + existing.getAmountPaid()
                    + ") cannot exceed the grand total (" + symbol + existing.getGrandTotal() + ").");
        }

        Appointment saved = saveWithConflictCheck(existing);

        if (walletDelta.signum() > 0) {
            walletService.applyToAppointment(existing.getPatient().getId(), saved.getId(), walletDelta);
        } else if (walletDelta.signum() < 0) {
            walletService.reverseForAppointment(existing.getPatient().getId(), saved.getId(), walletDelta.abs());
        }

        // Package consumption reconciliation — run after save so an insufficient-sessions failure
        // (another appointment consumed the last session concurrently) rolls back the whole update.
        reconcilePackageDelta(oldServiceItemCounts, newServiceItemCounts, serviceItemUnitAmounts, true, saved.getId());
        reconcilePackageDelta(oldProductItemCounts, newProductItemCounts, productItemUnitAmounts, false, saved.getId());

        log.info("Updated appointment id={}", saved.getId());
        return saved;
    }

    /**
     * Reconciles package-covered-line consumption after a full clear-and-rebuild, by occurrence
     * count per item id rather than by line identity (lines don't round-trip their own db id, and
     * the same item can legitimately back more than one line — see the caller's comment). For each
     * item id in the union of old/new keys: delta==0 is a no-op (covers both "unchanged, resubmitted
     * with the same id" and "never referenced" — never reverse-then-reapply per requirements §5.5),
     * delta&gt;0 consumes that many additional sessions, delta&lt;0 reverses that many.
     */
    private void reconcilePackageDelta(Map<Long, Integer> oldCounts, Map<Long, Integer> newCounts,
                                        Map<Long, BigDecimal> unitAmounts, boolean isService, Long appointmentId) {
        Set<Long> allItemIds = new LinkedHashSet<>();
        allItemIds.addAll(oldCounts.keySet());
        allItemIds.addAll(newCounts.keySet());
        for (Long itemId : allItemIds) {
            int delta = newCounts.getOrDefault(itemId, 0) - oldCounts.getOrDefault(itemId, 0);
            if (delta > 0) {
                BigDecimal amount = unitAmounts.get(itemId);
                for (int i = 0; i < delta; i++) {
                    if (isService) packageService.consumeServiceItem(itemId, appointmentId, amount);
                    else packageService.consumeProductItem(itemId, appointmentId, amount);
                }
            } else if (delta < 0) {
                for (int i = 0; i < -delta; i++) {
                    if (isService) packageService.reverseServiceItem(itemId, appointmentId);
                    else packageService.reverseProductItem(itemId, appointmentId);
                }
            }
        }
    }

    private Map<Long, Integer> tallyServicePackageItemCounts(List<AppointmentServiceLine> lines) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (AppointmentServiceLine sl : lines) {
            if (sl.getPackageServiceItem() != null) counts.merge(sl.getPackageServiceItem().getId(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<Long, Integer> tallyProductPackageItemCounts(List<AppointmentProductLine> lines) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (AppointmentProductLine pl : lines) {
            if (pl.getPackageProductItem() != null) counts.merge(pl.getPackageProductItem().getId(), 1, Integer::sum);
        }
        return counts;
    }

    private record PendingPackageConsumption(Long serviceItemId, Long productItemId, BigDecimal amount) {}

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
        appointmentRepository.lockForVersionBump(appointmentId);
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
        appointmentRepository.lockForVersionBump(appointmentId);
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
     * Drops any AppointmentCombo attached by buildComboSelections that ended up with no matching
     * service/product line — a malformed JS state or partial submit can send a combo selection whose
     * groupKey no line actually carries. Left unfiltered, applyComboDiscount would just no-op the
     * discount for it (see its lines.isEmpty() branch) while an orphaned, zero-item, ₹0-savings combo
     * still persists and shows up in the appointment detail page's "Combo Packages" section. Must run
     * after both line-building loops so every line's appointmentCombo reference is already set.
     */
    private void removeOrphanCombos(Appointment appointment) {
        appointment.getCombos().removeIf(ac ->
                appointment.getServiceLines().stream().noneMatch(sl -> sl.getAppointmentCombo() == ac)
                        && appointment.getProductLines().stream().noneMatch(pl -> pl.getAppointmentCombo() == ac));
    }

    /**
     * Phase 1 of the two-phase discount: resolves one combo's own discount against the raw
     * lineTotal of just that combo's lines, and distributes it across only those lines. Lines
     * outside any combo are untouched here (see distributeDiscount for the whole-appointment phase).
     */
    private void applyComboDiscount(Appointment appointment, AppointmentCombo ac) {
        List<ProportionalAllocator.AllocationLine> lines = new ArrayList<>();
        appointment.getServiceLines().stream()
                .filter(sl -> sl.getAppointmentCombo() == ac)
                .forEach(sl -> lines.add(new ProportionalAllocator.AllocationLine(sl.getLineTotal(), sl::setDiscountedLineTotal)));
        appointment.getProductLines().stream()
                .filter(pl -> pl.getAppointmentCombo() == ac)
                .forEach(pl -> lines.add(new ProportionalAllocator.AllocationLine(pl.getLineTotal(), pl::setDiscountedLineTotal)));

        BigDecimal comboSubtotal = lines.stream()
                .map(ProportionalAllocator.AllocationLine::rawAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
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
        ProportionalAllocator.distribute(lines, comboSubtotal, resolved);
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
        List<ProportionalAllocator.AllocationLine> lines = new ArrayList<>();
        appointment.getServiceLines().forEach(sl ->
                lines.add(new ProportionalAllocator.AllocationLine(sl.getEffectiveLineTotal(), sl::setDiscountedLineTotal)));
        appointment.getProductLines().forEach(pl ->
                lines.add(new ProportionalAllocator.AllocationLine(pl.getEffectiveLineTotal(), pl::setDiscountedLineTotal)));
        ProportionalAllocator.distribute(lines, subtotal, discountAmount);
    }

    private void validateDuration(Integer durationMinutes) {
        int maxDurationMinutes = properties.getAppointment().getMaxDurationMinutes();
        if (durationMinutes != null && durationMinutes > maxDurationMinutes) {
            throw new IllegalArgumentException(
                    "Appointment duration cannot exceed " + (maxDurationMinutes / 60) + " hours.");
        }
    }

    /**
     * Sums quantity per product across every line in a single submission (a product can appear on more
     * than one line — a standalone line plus a combo line, or two combo lines) and validates the
     * aggregate demand against live stock, so two lines of the same product can't each pass an
     * independent check blind to the other's demand.
     */
    private void validateAggregateStockDemand(List<AppointmentForm.ProductLineForm> productLines) {
        Map<Long, Integer> demandByProductId = new LinkedHashMap<>();
        for (AppointmentForm.ProductLineForm plf : productLines) {
            if (plf == null || plf.getProductId() == null) continue;
            int qty = Math.max(1, plf.getQuantity());
            demandByProductId.merge(plf.getProductId(), qty, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : demandByProductId.entrySet()) {
            Product product = productRepository.findById(entry.getKey())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found: " + entry.getKey()));
            if (product.getStockQuantity() < entry.getValue()) {
                throw new IllegalArgumentException(
                        "Insufficient stock for '" + product.getName()
                        + "'. Available: " + product.getStockQuantity()
                        + ", requested: " + entry.getValue());
            }
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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