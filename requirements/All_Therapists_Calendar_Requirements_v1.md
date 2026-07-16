# Healing House Clinic — All-Therapists Calendar

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 16, 2026
**Status:** Draft — decisions recorded below, ready for review before implementation
**Relation to core doc:** Extends `Therapist_Calendar_Requirements_v1.md` (Phase B, done) and `Therapist_Calendar_Interactive_Requirements_v1.md` (drag/resize/cancel, done). Those documents explicitly deferred an "all-therapists side-by-side view" as a non-goal (§2 of the Phase B doc); this is that follow-up, reusing the same conflict-detection, reschedule, and cancel plumbing.

---

## 1. Problem Statement

`GET /therapists/{id}/calendar` shows one therapist's schedule at a time, switchable via a dropdown. Staff at the front desk need to answer "who's free right now, across everyone?" and "how does today look for the whole clinic?" without clicking through each therapist one at a time. There is no single view that overlays every therapist's bookings together.

This document adds a new **all-therapists calendar** page, linked from the main dashboard, that overlays every selected therapist's appointments on one grid, color-coded per therapist, with checkboxes to show/hide individual therapists.

---

## 2. Goals

- A new page, **`GET /calendar`**, linked from the dashboard (`dashboard.html`), showing an overlay calendar of every active therapist's appointments on one grid.
- A **checkbox list** of active therapists above the calendar to select/deselect who's shown; defaults to **all active therapists checked**, and the selection is **remembered per-browser via `localStorage`** across visits.
- Each therapist gets a **stable, deterministic color** (hash of `therapistId` into a fixed palette) — no new schema/admin work required.
- Since color is now spent on therapist identity, appointment **status** (`CANCELLED`/`NO_SHOW`/`COMPLETED` vs `SCHEDULED`) is conveyed by a secondary visual cue (faded + hatched) instead of color.
- **Same interactivity as the existing single-therapist calendar:** click an appointment → detail page, click an empty slot → new-appointment form, drag to reschedule, resize to change duration, delete-icon to cancel — reusing the existing `/appointments/{id}/reschedule` and `/appointments/{id}/cancel-from-calendar` endpoints as-is. Therapist reassignment is **not** done from this calendar (still the existing per-line reassign flow on the appointment detail page).
- **Zero impact** on the existing single-therapist calendar page, its endpoints, or any other module — this is a new, additive page and one new data-feed endpoint.

### Non-goals (explicitly out of scope for this iteration)

- **Resource/side-by-side columns per therapist** ("real" resource-timeline view) — that requires FullCalendar Premium (Scheduler), a paid license, which this project does not currently have. Deferred; can be reconsidered later if the overlay view proves too crowded in practice.
- **Reassigning a therapist by dragging an event between therapists** — not possible without resource columns anyway; reassignment stays on the appointment detail page's existing per-line flow.
- **Per-therapist color customization by admin** — colors are auto-assigned from a fixed palette, not staff-editable, in this iteration.
- **Working-hours/business-hours enforcement, per-service default durations** — same non-goals as the original calendar doc; nothing new introduced here.

---

## 3. Domain Model Changes

None. No new entities, columns, or persisted fields — this feature is a read/interact layer over existing `Appointment`/`Therapist` data, same as the original calendar.

---

## 4. DTO Changes

### 4.1 `CalendarEventDTO` — one new field

```java
public record CalendarEventDTO(
        Long id,
        String title,
        String start,
        String end,
        String color,
        String status,
        Long therapistId   // NEW — which therapist this specific event belongs to
) {}
```

Additive change. `AppointmentService.toCalendarEvent` (used by both the existing single-therapist feed and the new multi-therapist feed) must pass a `therapistId` at every call site — see §5.2 for what that id means when one appointment involves more than one selected therapist.

No other new DTOs needed — reschedule/cancel reuse `RescheduleRequestDTO`/`RescheduleResponseDTO`/`CalendarActionResponseDTO` exactly as they exist today.

---

## 5. Business Rules

### 5.1 Color assignment

- A fixed, curated palette of ~10 visually distinct hex colors (Bootstrap-adjacent, chosen for contrast against both light and dark event text).
- A therapist's color = `palette[hash(therapistId) % palette.size()]` — deterministic, computed client-side (JS) or server-side, either is fine as long as it's the *same* formula everywhere so a therapist's color never changes between page loads or between this calendar and any future legend/reports. Recommend server-side (`AppointmentService`/a small `TherapistColorUtil`) so both the calendar feed and the checkbox-list legend swatches agree without duplicating the hash function in JS.
- With more therapists than palette colors, colors repeat (acceptable — clinics with 10+ simultaneously-active therapists are expected to feel some crowding regardless).

### 5.2 One appointment, possibly multiple events (multi-therapist involvement)

An appointment's main therapist and any per-line-reassigned therapist (`AppointmentServiceLine.therapist` / `AppointmentProductLine.therapist`) can differ — this is the same "busy" definition `AppointmentSpec.hasTherapistId` and conflict detection already use (§5.2 of the original calendar doc).

**Decision:** for every *selected* therapist who is involved in an appointment (as main therapist **or** on any line), render **one calendar event colored for that therapist**. If an appointment involves two selected therapists (e.g. main therapist A, with one line reassigned to therapist B), it renders as **two overlapping events** — one in A's color, one in B's color — because both therapists genuinely have that slot occupied, and hiding either one would misrepresent that specific therapist's schedule when the other is unchecked. This mirrors the existing single-therapist page's own logic (which already shows the appointment on a line-only therapist's calendar, labeled "(with {main therapist})").

- Each event's `title` = patient name, plus `" (with {other therapist's name})"` when the event's own `therapistId` is not the appointment's main therapist — identical labeling rule to the existing single-therapist feed.
- Each rendered FullCalendar event's **id is a composite** `"{appointmentId}-{therapistId}"` (not just `appointmentId`), so two events for the same appointment don't collide in FullCalendar's internal event store. The **real** appointment id for actions (open detail page, reschedule, cancel) travels separately via `extendedProps.appointmentId`.

### 5.3 Status rendering

- `SCHEDULED` events render solid/full-opacity in the therapist's color.
- `COMPLETED` / `CANCELLED` / `NO_SHOW` events render faded (reduced opacity) with a subtle diagonal-hatch background, keeping the therapist's color legible but visually distinct from active bookings — implemented as a CSS class toggled via `eventDidMount`/`classNames` keyed off `extendedProps.status`, same mechanism already used to gate `editable`/drag-handle visibility on the single-therapist calendar.
- Only `SCHEDULED` events get drag handles, resize handles, and a delete icon — identical editable-scope rule as the existing interactive calendar (§3.1 of the Interactive doc).

### 5.4 Therapist selection & persistence

- Checkbox list built from `TherapistRepository.findByActiveTrueOrderByFullNameAsc()` (same query the existing therapist dropdown uses) — inactive therapists never appear as an option.
- Default: **all checked** on first visit (no `localStorage` entry yet).
- On every change, write the checked set to `localStorage` (e.g. key `hh.calendar.selectedTherapistIds`, JSON array of ids) and re-fetch calendar events for the new selection. On page load, if a `localStorage` entry exists, use it as the initial checked set instead of "all"; therapists that no longer exist/are no longer active are silently dropped from the stored set rather than erroring.
- A "Select All" / "Select None" convenience pair above the checkbox list.

### 5.5 Reschedule / resize / cancel — refetch, don't locally patch

Because one appointment can be represented by **two** visual events (§5.2), the existing single-therapist pattern of optimistically mutating just the dragged `FullCalendar` event object is not safe here — the "twin" event for the other involved therapist would silently go stale (wrong time, or not removed on cancel).

**Rule:** after any successful reschedule, resize, or cancel action, call `calendar.refetchEvents()` to reload the visible range from the server (which will correctly move/remove *all* events tied to that appointment id) rather than patching the single dragged/clicked event in place. On a *failed* reschedule/resize, still use `info.revert()` for the immediate visual snap-back (no server round-trip needed to know the old position), matching today's behavior.

### 5.6 No impact on unrelated logic

Identical to the Interactive doc's §3.4 — reschedule/resize/cancel only ever touch `appointmentDateTime`, `durationMinutes`, and `status`; commission, discount, wallet, stock, and reporting are untouched by anything in this document.

---

## 6. Service / Controller Changes

### 6.1 `AppointmentService` — new query method

```java
@Transactional(readOnly = true)
public List<CalendarEventDTO> findCalendarEventsForTherapists(
        List<Long> therapistIds, LocalDateTime start, LocalDateTime end) {

    Specification<Appointment> spec = Specification
            .where(AppointmentSpec.withPatientAndTherapist())
            .and(Specification.anyOf(
                    therapistIds.stream().map(AppointmentSpec::hasTherapistId).toList()))
            .and(AppointmentSpec.betweenDates(start.minusDays(1), end.plusDays(1)));

    List<Appointment> appointments = appointmentRepository.findAll(spec);

    // One event per (appointment, selected therapist actually involved) pair — see §5.2.
    return appointments.stream()
            .flatMap(a -> therapistIds.stream()
                    .filter(tid -> isTherapistInvolved(a, tid))
                    .map(tid -> toCalendarEvent(a, tid)))
            .toList();
}
```

`isTherapistInvolved(Appointment, Long)` — main therapist id equals, or any service/product line's therapist id equals (small helper, or reuse `hasTherapistId` semantics in-memory over the already-loaded lines rather than re-querying).

`toCalendarEvent` gains the `therapistId` field on the returned record (§4.1); no other change to its existing title/color/status logic.

### 6.2 `AppointmentController` — new endpoint

A **new, separate route** (not an overload of the existing `/calendar-feed`, to avoid ambiguous `@RequestParam` binding between a single required `therapistId` and a multi-value param on the same path):

```java
@GetMapping("/calendar-feed-multi")
@ResponseBody
public List<CalendarEventDTO> calendarFeedMulti(@RequestParam List<Long> therapistIds,
                                                 @RequestParam String start,
                                                 @RequestParam String end) {
    return appointmentService.findCalendarEventsForTherapists(
            therapistIds, parseCalendarBound(start), parseCalendarBound(end));
}
```

Called as `GET /appointments/calendar-feed-multi?therapistIds=1,2,3&start=...&end=...` — Spring binds a comma-separated query value directly to `List<Long>`. Reuses the existing `parseCalendarBound` helper unchanged.

### 6.3 New page controller

`GET /calendar` (e.g. on `DashboardController` or a new small `CalendarController` — implementer's choice) renders a new `templates/calendar.html`, passing the active therapist list (for the checkbox panel) the same way `therapists/calendar.html` already receives it.

---

## 7. UI / Template Changes

### 7.1 `dashboard.html`

Add a link/card/button — e.g. near the existing KPI cards or navbar — to `GET /calendar`, labeled something like "All-Therapists Calendar."

### 7.2 New `templates/calendar.html`

Modeled closely on `therapists/calendar.html`, with these differences:

- **Therapist checkbox panel** instead of a single-select dropdown — list of active therapists, each row showing a small color swatch (matching §5.1's palette assignment) next to the name and a checkbox; "Select All" / "Select None" buttons; state read from / written to `localStorage` per §5.4.
- **Views:** `timeGridDay` (default), `timeGridWeek`, and `dayGridMonth`, matching the single-therapist calendar's view set. The view-switcher buttons always render in the header toolbar (top), on both desktop and mobile — no separate mobile-only footer toolbar or list-view fallback; the same header/default-view config is used regardless of screen size.
- **Event source:** `GET /appointments/calendar-feed-multi?therapistIds=...` (§6.2), re-fetched whenever the checkbox selection changes (`calendar.refetchEvents()`), not just on navigation.
- **Event color:** `event.color` (already resolved by the palette on the server per §5.1) instead of the status-based color the single-therapist page uses.
- **Event status styling:** `classNames` callback (or `eventDidMount`) applies a `fc-event-inactive` class when `extendedProps.status !== 'SCHEDULED'`, per §5.3.
- **Editability:** identical `editable`/`startEditable`/`durationEditable` gating on `status === 'SCHEDULED'`, identical delete-icon (`eventDidMount`) and cancel-confirm-modal pattern, copied from `therapists/calendar.html`.
- **Reschedule/resize/cancel handlers:** same POST calls to `/appointments/{id}/reschedule` and `/appointments/{id}/cancel-from-calendar` (reading the real id from `extendedProps.appointmentId`, not the composite FullCalendar `id`), but on success call `calendar.refetchEvents()` instead of mutating/removing the single event object (§5.5).
- **Click-through:** `eventClick` navigates to `/appointments/{extendedProps.appointmentId}`; `dateClick` navigates to `/appointments/new` — but with **no** `therapistId` pre-filled (unlike the single-therapist page), since an empty-slot click on the overlay isn't tied to one specific therapist. Staff pick the therapist on the new-appointment form as normal.

### 7.3 No changes required to

`therapists/calendar.html`, its existing `/calendar-feed`/`/reschedule`/`/cancel-from-calendar` endpoints' behavior, `appointments/list.html`, `appointments/detail.html`, reports, exports, or any commission/discount/wallet/package logic.

---

## 8. Acceptance Criteria

1. `GET /calendar` renders an overlay calendar showing every **checked** active therapist's appointments in that therapist's own stable color.
2. Unchecking a therapist hides their events (and, per §5.2, only *their* copy of any shared appointment — the appointment still shows for any other checked therapist involved in it); the checked/unchecked state persists across page reloads on the same browser.
3. A therapist's assigned color is identical every time it's computed (same hash formula), including across separate page loads.
4. `SCHEDULED` events render solid; `COMPLETED`/`CANCELLED`/`NO_SHOW` events render faded/hatched and are not draggable/resizable and show no delete icon.
5. An appointment where two *checked* therapists are each involved (main + a reassigned line) renders as two distinct, correctly colored, overlapping events.
6. Dragging or resizing a `SCHEDULED` event re-runs the existing conflict check (`findConflicts`) exactly as today's single-therapist calendar does, including the "Save anyway" override; a successful change refreshes the whole visible range (so any twin event for the same appointment also moves/updates).
7. Cancelling from the calendar's delete icon cancels the appointment via the existing `cancelAppointment` path and removes **all** of that appointment's visual events (both twins, if any) via a refetch.
8. Clicking an event navigates to that appointment's detail page; clicking empty grid space navigates to the new-appointment form with no therapist pre-filled.
9. The existing `/therapists/{id}/calendar` page, its endpoints, and every other existing module (commission, discount, wallet, packages, reports) are unaffected — verified by the existing test suite passing unchanged.

---

## 9. Decided (Open Questions Resolved)

- **Layout:** Overlay, single grid, color-coded by therapist (not FullCalendar Premium resource columns) — avoids a licensing cost, stays on the existing free CDN stack. Confirmed by clinic owner, July 16, 2026.
- **Entry point:** New standalone page (`GET /calendar`), linked from the dashboard; the existing single-therapist page is untouched. Confirmed by clinic owner, July 16, 2026.
- **Interactivity:** Full parity with the existing single-therapist calendar — drag/resize/cancel all work the same way, reusing the same endpoints. Confirmed by clinic owner, July 16, 2026.
- **Status cue:** Faded + hatched for non-`SCHEDULED` events, since color is now spent on therapist identity. Confirmed by clinic owner, July 16, 2026.
- **Color source:** Auto-assigned from a fixed palette via a deterministic hash of `therapistId` — no admin-facing color picker in this iteration. Confirmed by clinic owner, July 16, 2026.
- **Default selection & persistence:** All active therapists checked by default; selection remembered via `localStorage` per browser. Confirmed by clinic owner, July 16, 2026.
- **Multi-therapist appointment rendering:** One event per selected-and-involved therapist (so a main+reassigned-line appointment can show as two overlapping, differently-colored events) — architect's call, matching the existing single-therapist page's own "busy" definition; flagged here for the clinic owner to override if a single merged event is preferred instead.
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

---

## 10. Suggested Phased Implementation

Single phase — this is additive and fairly small relative to the original calendar feature:

- Backend: `CalendarEventDTO.therapistId` field, `AppointmentService.findCalendarEventsForTherapists`, `AppointmentController.calendarFeedMulti`, palette/hash helper.
- Frontend: new `templates/calendar.html` (adapted from `therapists/calendar.html`), checkbox panel + `localStorage` persistence, dashboard link.
- No dependency on any other in-flight work; can ship independently once reviewed.

---

*Document Version 1.0 — Healing House Clinic — July 2026*