# Healing House Clinic — Therapist Calendar & Booking Conflict Detection

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 11, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Modifies the **Appointment** module (Phase 2, done) and adds a new read-only calendar view on top of existing appointment data. Builds on `Per_Line_Therapist_Assignment_Requirements_v1.md` (per-line therapist assignment, done) since conflict detection must account for it.

---

## 1. Problem Statement

Today, booking an appointment only requires a patient, a main therapist, and a date/time (`Appointment.appointmentDateTime`). There is:

- **No concept of duration** — an appointment has a start moment but no stored end time, so the system has no idea how long a therapist is occupied.
- **No conflict detection** — nothing stops two different patients from being booked with the same therapist at overlapping times. This is currently caught only if staff manually notice it while scanning the appointments list.
- **No visual schedule** — there is no way to see a therapist's day/week/month at a glance (list view only, filterable but not calendar-shaped). Staff can't quickly answer "is Therapist X free at 3pm Thursday?" without scanning/filtering the list.

This document defines: (1) a stored appointment duration, (2) a conflict warning at booking/edit time, and (3) a visual, Outlook-style calendar per therapist.

---

## 2. Goals

- Every appointment has an explicit **duration in minutes**, defaulting to **60**, editable by staff at booking time (a patient may add extra services on the spot, or the visit may be shorter/longer than default).
- When saving a new or edited appointment, warn staff if the chosen therapist is already booked for an overlapping time — **but allow them to save anyway after confirming**. Double-booking is sometimes intentional (e.g. a patient resting between treatments, a therapist briefly assisting elsewhere); the system should flag it, not forbid it.
- "Already booked" must account for **both** the main therapist and any therapist assigned at the line-item level (per `Per_Line_Therapist_Assignment_Requirements_v1.md`) — a therapist who only handles one line on someone else's appointment is still occupied for that appointment's full window.
- Add a **read-only visual calendar** page, per therapist, with day/week/month views (Outlook-style grid), so staff can see a schedule at a glance instead of scanning the filtered list.
- **Zero impact on existing data or flows that don't touch time/duration** — commission, discounts, stock, reports, exports, and every other module are untouched.

### Non-goals (explicitly out of scope for this iteration)

- **Hard blocking** of double-bookings — this is a warning-and-confirm flow, never a forced rejection.
- **Interactive calendar editing** — no drag-to-reschedule, no resize-to-change-duration, no click-and-create directly on the calendar grid. The calendar is a *viewer*; creating/editing appointments still goes through the existing `appointments/form.html` (the calendar just deep-links into it with the therapist/time pre-filled).
- **All-therapists side-by-side view** ("resource view" showing every therapist as a column at once) — v1 is single-therapist-at-a-time with a dropdown switcher. Can be a fast follow-up once the single-therapist view is validated with staff.
- **Clinic business hours / working-hours enforcement** — no such concept exists in the system today (no open/close time, no per-therapist leave or day-off tracking). The calendar and conflict check operate purely on stored appointment times; they don't validate against "the clinic is closed" or "this therapist is on leave." Could be a future requirement if needed.
- **Per-service default durations** (e.g. "Deep Tissue Massage defaults to 90 min") — every appointment gets the same 60-min default regardless of which services are added; staff adjust manually. A smarter per-service default could be a future enhancement once real usage data shows it's worth it.
- **Locking/frozen calendar snapshots** — like the rest of the system, the calendar always reflects live data; editing an appointment's time/duration immediately moves it on the calendar.

---

## 3. Domain Model Changes

### 3.1 `Appointment` — new field

```java
@Column(nullable = false)
@Builder.Default
private Integer durationMinutes = 60;
```

- Add a transient convenience getter:

```java
@Transient
public LocalDateTime getEndDateTime() {
    return appointmentDateTime.plusMinutes(durationMinutes);
}
```

### 3.2 Existing Data / Rollout

`hibernate.ddl-auto: update` auto-adds the `duration_minutes` column on next startup. Since it's `NOT NULL`, existing rows need a default value in the same deploy:

- **Backfill rule:** every existing appointment gets `duration_minutes = 60`, regardless of what services/products it contains. This is a display-only backfill (no historical conflict re-check is performed against past data) — same lossless-default pattern used for the per-line therapist backfill.
- Run as a one-off `UPDATE appointment SET duration_minutes = 60 WHERE duration_minutes IS NULL` (or equivalent), before the `NOT NULL` constraint is enforced — same ordering caveat as the per-line therapist backfill.

### 3.3 ER Diagram Update

No new entities or relationships — `Appointment` gains one scalar column. No Mermaid diagram change needed beyond noting the new attribute in the entity's field list.

---

## 4. DTO Changes

### 4.1 `AppointmentForm`

```java
private Integer durationMinutes = 60;
```

### 4.2 `AppointmentForm.from(Appointment appt)`

Add: `f.setDurationMinutes(appt.getDurationMinutes());`

---

## 5. Business Rules

### 5.1 Duration

- Default `60` minutes, shown as an editable numeric field (minutes) or a simple dropdown (30 / 60 / 90 / 120 / custom) next to the date/time field on the appointment form — implementer's choice on control type, dropdown is likely friendlier for tablet/mobile use.
- No minimum/maximum enforced by the system beyond basic sanity (must be a positive integer). Staff judgment governs realistic values.

### 5.2 Conflict Detection Scope

A therapist is considered **busy** for the interval `[appointmentDateTime, appointmentDateTime + durationMinutes)` of every appointment where they are either:
1. The **main therapist** (`Appointment.therapist`), or
2. Assigned to **any service or product line** on that appointment (`AppointmentServiceLine.therapist` / `AppointmentProductLine.therapist`).

Only appointments with status `SCHEDULED` or `COMPLETED` count as occupying a slot. `CANCELLED` and `NO_SHOW` appointments never trigger a conflict (the therapist was effectively freed for that time).

Two intervals conflict if they overlap at all (standard `startA < endB AND startB < endA` check) — back-to-back appointments (one ending exactly when another starts) are **not** a conflict.

### 5.3 Warn-and-Override Flow

- On submitting the appointment form (create or edit), the server checks for conflicts for **every therapist involved** (main + all line therapists) against all other appointments (excluding the one being edited, if editing).
- If any conflict is found: **do not save yet.** Re-render the form with a clear warning banner listing each conflicting therapist, the conflicting appointment's patient name, and its time range (e.g. *"Therapist Anjali Sharma is already booked 3:00–4:00 PM with Rohan Mehta (Appt #142)."*). Include a **"Save anyway"** confirmation control (e.g. a checkbox or a second submit button) that, when checked/clicked, resubmits and bypasses the warning to persist the appointment.
- This mirrors a typical "soft validation" pattern: first submit surfaces the warning, a deliberate second action confirms intent. No appointment is ever silently saved past a conflict without the user seeing it at least once.

### 5.4 Editing Existing Appointments

- Changing the date/time, duration, main therapist, or any line's therapist on an existing appointment re-runs the same conflict check (excluding the appointment's own current row) before saving, following the same warn-and-override flow.

---

## 6. UI / Template Changes

### 6.1 `templates/appointments/form.html`

- Add a duration control next to the appointment date/time field, defaulting to 60 minutes (see §5.1).
- Add a conflict-warning banner (Bootstrap `alert-warning`) rendered above the form when the controller detects conflicts on submit, listing each conflict as described in §5.3, plus a "Save anyway" checkbox/button to resubmit and force-save.

### 6.2 New: Therapist Calendar Page

- **Route:** `GET /therapists/{id}/calendar` (or a standalone `GET /calendar?therapistId=`) — implementer's choice, but nesting under the therapist detail page (`/therapists/{id}`) as a tab/link is consistent with the existing Therapist Details page pattern.
- **Library:** [FullCalendar](https://fullcalendar.io/) (JS, MIT-licensed) via CDN `<script>`/`<link>` tags — no npm/build step, consistent with the project's existing Bootstrap 5 + Chart.js CDN approach.
- **Views:** day, week (`timeGridDay` / `timeGridWeek`), and month (`dayGridMonth`), with FullCalendar's built-in view-switcher toolbar.
- **Therapist selector:** a dropdown above the calendar (populated from the active therapist list, same pattern as other therapist dropdowns in the app) to switch whose calendar is shown; defaults to the therapist whose detail page it was opened from.
- **Data feed:** `GET /appointments/calendar-feed?therapistId=&start=&end=` returns JSON events for the visible range — reuses `AppointmentService`/`AppointmentSpec` query logic (therapist-as-main OR therapist-on-any-line, matching §5.2's scope) rather than a new repository path. Each event: title (patient name + main therapist if viewing shows a line-only appointment), start, end (`appointmentDateTime` + `durationMinutes`), and a color keyed to `status` — reusing the same status→color mapping already used in `appointments/list.html` (`COMPLETED` → green/`bg-success`, `CANCELLED` → red/`bg-danger`, `NO_SHOW` → yellow/`bg-warning`, `SCHEDULED` → default/primary).
- **Interactions (read-only, per §2 non-goals):**
  - Clicking an existing appointment event navigates to its existing detail page (`GET /appointments/{id}`).
  - Clicking an empty time slot navigates to the existing "new appointment" form (`GET /appointments/new`) with `therapistId` and `appointmentDateTime` pre-filled via query params.
- **Navigation:** add a "Calendar" link/button on the Therapist Details page (`/therapists/{id}`) and optionally on the Appointments list page for quick access.

### 6.3 No changes required to

`appointments/list.html`, `appointments/detail.html`, reports, exports, dashboard, commission/CSV/PDF logic — none of them read `durationMinutes` or are affected by the conflict check, which only runs at save time.

---

## 7. Acceptance Criteria

1. Every appointment (new and existing, post-backfill) has a `durationMinutes` value; new appointments default to 60 and are editable on the form.
2. Saving an appointment (create or edit) whose therapist — main or any line — overlaps another `SCHEDULED`/`COMPLETED` appointment's time window shows a clear warning naming the conflicting therapist, patient, and time, and does **not** save until the user explicitly confirms.
3. Confirming past the warning saves the appointment as normal; declining/editing the time or therapist and resubmitting re-checks for conflicts.
4. `CANCELLED` and `NO_SHOW` appointments never trigger a conflict warning.
5. Back-to-back appointments (end time of one == start time of the next) do not trigger a conflict warning.
6. A therapist's calendar page shows day/week/month views of their schedule (as main therapist or line therapist), color-coded by appointment status, switchable to any other active therapist via dropdown.
7. Clicking a calendar event opens that appointment's existing detail page; clicking an empty slot opens the existing new-appointment form pre-filled with that therapist and time.
8. No regressions to commission, discount, stock, reporting, CSV/PDF export, or any existing appointment list/detail/patient-history behavior — all of that logic is untouched by this change.

---

## 8. Decided (Open Questions Resolved)

- **Conflict enforcement:** Warn-and-allow-override, never a hard block. Confirmed by clinic owner, July 11, 2026.
- **Conflict scope:** Includes both the main therapist and any per-line therapist — a therapist handling just one line on someone else's appointment still counts as busy for that window. Confirmed by clinic owner, July 11, 2026.
- **Calendar interactivity (v1):** Read-only viewer that deep-links into the existing appointment form/detail pages — no drag/resize/inline-create in this iteration. Confirmed by clinic owner, July 11, 2026.
- **Calendar layout (v1):** Single-therapist view with a dropdown switcher; an all-therapists side-by-side view is deferred to a future iteration. Confirmed by clinic owner, July 11, 2026.
- **Default duration:** 60 minutes, editable per appointment, no per-service smart defaults in this iteration.
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

---

## 9. Suggested Phased Implementation

Given the size, this is best split into two independent, separately-shippable phases:

**Phase A — Duration + Conflict Detection (backend + form only, no calendar UI)**
- Domain/DTO changes (§3, §4), backfill (§3.2), conflict-check service method, warn-and-override flow on the appointment form (§5, §6.1).
- Ships value immediately (double-booking protection) without needing the calendar UI at all.

**Phase B — Therapist Calendar View**
- New route, FullCalendar integration, data-feed endpoint, therapist selector, navigation entry points (§6.2).
- Depends on Phase A only for `durationMinutes` existing (so events have a real end time) — otherwise independent.

Recommend implementing and shipping Phase A first, confirming it works end-to-end with real bookings, then adding Phase B on top.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
