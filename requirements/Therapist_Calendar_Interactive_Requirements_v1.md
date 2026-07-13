# Healing House Clinic — Interactive Therapist Calendar (Drag/Resize/Delete)

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 12, 2026
**Status:** Draft — decisions recorded below, ready for implementation
**Relation to core doc:** Extends `Therapist_Calendar_Requirements_v1.md` (Phase B, done — read-only calendar). That document explicitly listed drag/resize/inline-delete as **non-goals for v1** (§2); this is the follow-up that adds them, reusing the same `Appointment.durationMinutes` field and `findConflicts` conflict-detection logic from Phase A.

---

## 1. Problem Statement

The therapist calendar (`GET /therapists/{id}/calendar`) currently only views the schedule — rescheduling, extending/shortening a visit, or cancelling still requires leaving the calendar and going through `appointments/{id}/edit` or the detail page. Staff want to do all three directly on the calendar grid: drag an event to a new day/time, drag its edge to change duration, and cancel it without navigating away.

## 2. Goals

- **Drag-to-reschedule:** dragging an event to a new slot updates `appointmentDateTime` (date and/or time), keeping `durationMinutes` unchanged.
- **Resize-to-change-duration:** dragging an event's end edge updates `durationMinutes`, keeping `appointmentDateTime` unchanged.
- **Delete-from-calendar:** a small control on the event cancels the appointment (maps to the existing `CANCELLED` status — there is no hard-delete of appointments anywhere in this system).
- **Conflict detection reused, not reimplemented:** every drag/resize re-runs the same busy-window check as the appointment form (`AppointmentService.findConflicts` — main therapist + all line therapists, `SCHEDULED`/`COMPLETED` only, no back-to-back conflict), shown inline with a save-anyway override, mirroring §5.3 of the Phase A/B document.
- **Snap-to-15-minutes:** drag and resize move in 15-minute increments (`slotDuration`/`snapDuration` in FullCalendar), avoiding odd times like 3:07 PM.
- **Optimistic UI with revert:** the event moves immediately for responsiveness; if the server rejects the change (conflict declined, concurrent edit, appointment no longer `SCHEDULED`), the event snaps back to its prior position/size and an error toast is shown.

### Non-goals (explicitly out of scope)

- **Hard delete of appointments** — "delete" always means cancel (`status → CANCELLED`), consistent with the rest of the app.
- **Dragging/resizing/deleting non-`SCHEDULED` appointments** — `COMPLETED` (stock already decremented, commission already earned), `CANCELLED`, and `NO_SHOW` events render read-only on the calendar (no drag handles, no delete control), same as the existing rule that line items/discount are only editable while `SCHEDULED`.
- **Editing patient, therapist, services/products, discount, or wallet from the calendar** — those still require the full form. Drag/resize/delete only ever touch `appointmentDateTime`, `durationMinutes`, and `status`.
- **All-therapists resource view** — still out of scope per the Phase B document; unaffected by this change.
- **Working-hours/business-hours enforcement** — still not modeled anywhere in the system; drag/resize allow any time, same as manual entry on the form today.

---

## 3. Business Rules

### 3.1 Editable scope

Only appointments with `status == SCHEDULED` are draggable, resizable, and show a delete control. `COMPLETED`/`CANCELLED`/`NO_SHOW` events are rendered with `editable: false, startEditable: false, durationEditable: false` (per-event override) and no delete icon.

### 3.2 Reschedule (drag) and resize

Both actions hit the same new lightweight endpoint (`POST /appointments/{id}/reschedule`, JSON in/out — see §5.2) rather than the full `update` flow, since only two fields ever change:

- **Drag** sends the event's new `start` (and FullCalendar's unchanged `end`, from which `durationMinutes` is derived — kept equal to the pre-drag duration) as the new `appointmentDateTime`.
- **Resize** sends the unchanged `start` and the new `end`, from which the new `durationMinutes` is derived.
- Server re-validates `status == SCHEDULED` (defense in depth — the UI already hides drag handles for other statuses, but a stale page or concurrent status change must still be caught).
- Server runs `findConflicts` against the *proposed* `appointmentDateTime`/`durationMinutes`, excluding the appointment's own id — identical semantics to the form's conflict check.
  - **No conflicts:** persist `appointmentDateTime`/`durationMinutes`, return `{success: true}`. No other field is touched — no discount/wallet/commission recalculation, since `grandTotal` doesn't depend on time.
  - **Conflicts found, `forceSave` not set:** don't persist. Return `{success: false, conflicts: [...]}` (same `TherapistConflictDTO` shape used by the form).
  - **Conflicts found, `forceSave: true`:** persist anyway (same override semantics as the form's "Save anyway").
- On any non-2xx response or `success: false` without an override choice, the client reverts the event to its original position/size via FullCalendar's `revert()` (available on the `eventDrop`/`eventResize` info object) and shows a Bootstrap toast/alert with the conflict details, offering a "Save anyway" action that resubmits with `forceSave: true` and, if accepted, applies the drop without another round-trip revert.

### 3.3 Delete (cancel)

- Each `SCHEDULED` event renders a small delete icon (e.g. a `×` in the event's custom content via FullCalendar's `eventContent` callback). Clicking it stops event propagation (so it doesn't also trigger the existing "navigate to detail page" `eventClick` handler) and opens a confirm step before cancelling — a destructive action must not fire on a single accidental tap.
- Confirmed delete calls `POST /appointments/{id}/cancel` — the **existing** endpoint (`AppointmentController.cancel`, `AppointmentService.cancelAppointment`), which already restores product stock and reverses any applied wallet amount. No new cancel logic is needed.
- Since that endpoint currently returns a redirect (`RedirectAttributes` + `redirect:...`) for the form-based cancel flow, the calendar calls it via a background `fetch` with a small reason string (e.g. `"Cancelled via calendar"") and, on success, removes the event from the calendar client-side (`event.remove()`) rather than reloading the page — leaving `appointments/detail.html`'s existing cancel button/flow completely untouched.

### 3.4 No impact on unrelated logic

Reschedule/resize never touch `grandTotal`, `amountPaid`, `discountAmount`, `walletAmountApplied`, line items, or product stock — those all key off status transitions (create/complete/cancel), not off time. Delete reuses the existing cancel path exactly, so wallet reversal/stock restoration behave identically to cancelling from the detail page.

---

## 4. Domain / DTO Changes

No new entities or columns — `durationMinutes` already exists (Phase A). One new DTO for the reschedule request/response pair:

```java
public record RescheduleRequestDTO(
    LocalDateTime appointmentDateTime,
    Integer durationMinutes,
    boolean forceSave
) {}

public record RescheduleResponseDTO(
    boolean success,
    List<TherapistConflictDTO> conflicts
) {}
```

## 5. Service / Controller Changes

### 5.1 `AppointmentService.rescheduleAppointment`

```java
public RescheduleResponseDTO rescheduleAppointment(Long id, LocalDateTime newStart, int newDuration, boolean forceSave) {
    Appointment appt = getById(id);
    if (appt.getStatus() != AppointmentStatus.SCHEDULED) {
        throw new IllegalStateException("Only SCHEDULED appointments can be rescheduled.");
    }

    AppointmentForm probe = AppointmentForm.from(appt); // reuse existing lines/therapist snapshot
    probe.setAppointmentDateTime(newStart);
    probe.setDurationMinutes(newDuration);

    List<TherapistConflictDTO> conflicts = findConflicts(probe, id);
    if (!conflicts.isEmpty() && !forceSave) {
        return new RescheduleResponseDTO(false, conflicts);
    }

    appt.setAppointmentDateTime(newStart);
    appt.setDurationMinutes(newDuration);
    appointmentRepository.save(appt);
    return new RescheduleResponseDTO(true, List.of());
}
```

Reuses `findConflicts` as-is (already accounts for main + line therapists); no changes needed to that method.

### 5.2 `AppointmentController` — new endpoint

```java
@PostMapping("/{id}/reschedule")
@ResponseBody
public RescheduleResponseDTO reschedule(@PathVariable Long id, @RequestBody RescheduleRequestDTO req) {
    return appointmentService.rescheduleAppointment(
        id, req.appointmentDateTime(), req.durationMinutes(), req.forceSave());
}
```

No change to the existing `POST /{id}/cancel` endpoint — the calendar calls it as-is.

---

## 6. UI / Template Changes (`therapists/calendar.html`)

- `editable: true` on the FullCalendar config, `snapDuration: '00:15:00'`, `slotDuration` unchanged.
- Per-event `editable`/`startEditable`/`durationEditable` set to `false` when `status !== 'SCHEDULED'` (requires `CalendarEventDTO` to carry `status`, or infer editability from the existing `color` — cleaner to add a `status` field to the DTO so JS doesn't hardcode color→status mapping).
- `eventContent`: for `SCHEDULED` events, render the existing title plus a small trailing `×` delete affordance; other statuses render title only (matches read-only intent).
- `eventDrop(info)` / `eventResize(info)`: compute new `start`/`end` from `info.event`, POST to `/appointments/{id}/reschedule`; on `success:false` call `info.revert()` and show conflicts (with a "Save anyway" action that re-POSTs `forceSave: true` and, on success, does *not* revert); on network/server error also `info.revert()`.
- Delete click handler: confirm step → `fetch('/appointments/{id}/cancel', {method:'POST', body: reason})` → `info.event.remove()` on success, toast on failure.
- Existing `eventClick` (navigate to detail) and `dateClick` (navigate to new-appointment form) behavior is unchanged for clicks that don't hit the delete affordance.

### 6.1 `CalendarEventDTO` change

Add a `status` field (`String`, e.g. `"SCHEDULED"`) so the template can gate editability/delete-affordance without re-deriving it from color:

```java
public record CalendarEventDTO(Long id, String title, String start, String end, String color, String status) {}
```

---

## 7. Acceptance Criteria

1. Dragging a `SCHEDULED` event to a new day/time updates `appointmentDateTime` only; `durationMinutes` is unchanged.
2. Resizing a `SCHEDULED` event's edge updates `durationMinutes` only; `appointmentDateTime` is unchanged.
3. Both actions re-check conflicts (main + line therapists, `SCHEDULED`/`COMPLETED` only, no back-to-back conflict) before saving; a conflict blocks the save, reverts the event visually, and offers "Save anyway."
4. `COMPLETED`, `CANCELLED`, and `NO_SHOW` events cannot be dragged, resized, or deleted from the calendar (no handles, no delete icon).
5. Clicking the delete affordance on a `SCHEDULED` event, after confirmation, cancels it via the existing `cancelAppointment` path (product stock restored, wallet reversed if applied) and removes it from the calendar view without a full page reload.
6. No change to `grandTotal`, `amountPaid`, discount, wallet, commission, or reporting figures as a result of reschedule/resize/delete — reschedule/resize touch only time fields; delete reuses the existing, already-correct cancel logic.
7. Existing read-only behaviors (click event → detail page, click empty slot → new appointment form) continue to work for non-delete clicks.

---

## 8. Decided (Open Questions Resolved)

- **Delete semantics:** Maps to existing cancel (`status → CANCELLED`) — no hard-delete concept introduced. Confirmed July 12, 2026.
- **Editable scope:** `SCHEDULED` only; other statuses are read-only on the calendar. Confirmed July 12, 2026.
- **Conflict UX on drag/resize:** Inline confirm with "Save anyway," event reverts if declined — never a silent block, never a silent force-save. Confirmed July 12, 2026.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
