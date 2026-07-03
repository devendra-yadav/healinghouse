# Healing House Clinic — Patient History & Detail View
## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 4, 2026
**Status:** Approved — ready for implementation, all open questions resolved
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Builds on the **Patient** module (Phase 1, done) and the **Appointment** module (Phase 2, done). Can be implemented as **Phase 2.5**, independently of Phase 3 (Dashboard/Reports).

---

## 1. Problem Statement

Today there is no way to answer "What has this patient done at the clinic over time?" The `patients/list` page only offers **Edit** and **Deactivate**. A patient's appointment history exists in the database (`Appointment.patient` relationship) but is not surfaced anywhere — you'd have to filter the global Appointments list by typing the patient's name every time, and there is no lifetime summary (visit counts, spend, favorite therapist/service, etc.).

This document defines a **Patient Detail page** that consolidates a patient's profile, lifetime summary stats, and full appointment history (with filters) in one place.

---

## 2. Goals

- Add a **View** button next to Edit/Deactivate on the patient list, opening a dedicated Patient Detail page.
- Show **all patient details** already captured (contact info, DOB/age, address, medical history, allergies, notes, active status).
- Show **summary stats** computed across the patient's appointment history.
- Show the **full appointment history** as a filterable list below the summary stats.
- Each history row has **View** and **Edit** buttons that reuse the existing appointment detail/edit pages (`/appointments/{id}` and `/appointments/{id}/edit}`), not new pages.

### Non-goals (explicitly out of scope for this iteration)
- CSV export / print-friendly view of patient history (deferred — can be added later, same pattern as the Phase 3 reports CSV export).
- Editing medical history/allergies/notes inline from this page (still done via the existing Edit Patient form).
- Multi-patient comparison or clinic-wide "top patients" reporting (that belongs in Phase 3 reports, not here).

---

## 3. User Entry Point

**Patient List (`patients/list.html`)**
- Add a **View** button/icon per row, placed before Edit. Links to `GET /patients/{id}`.
- Existing **Edit** (`/patients/{id}/edit`) and **Deactivate** (`POST /patients/{id}/delete`) buttons stay as-is.

---

## 4. Patient Detail Page — Layout

`GET /patients/{id}` → new template `patients/detail.html`, using the standard `fragments/layout.html`.

Page is composed of three stacked sections:

### 4.1 Section A — Patient Profile Card
All fields from the `Patient` entity, formatted for reading (not a form):
- Full name, phone, email, gender
- Date of birth + computed age (`patient.getAge()`)
- Address
- Medical history, allergies, notes (each shown clearly, e.g. as labeled text blocks; empty fields show "—")
- Active/Inactive badge
- Patient since (`createdAt`)
- An **Edit** button linking to `/patients/{id}/edit` for changing any of the above.

### 4.2 Section B — Summary Stats

Computed from **all** of the patient's appointments (not affected by the history filters in Section C). Presented as KPI cards, e.g.:

**Visit counts**
- Total appointments (all statuses)
- Completed count
- Cancelled count
- No-show count

**Lifetime financials** (based on `COMPLETED` appointments)
- Total revenue (services + products, i.e. sum of `grandTotal`)
- Total amount paid
- Total outstanding balance (sum of `getBalanceDue()`)

**Recency & relationships**
- Last visit date (most recent `COMPLETED` appointment's `appointmentDateTime`)
- Most-seen therapist (therapist with the highest count of appointments for this patient) + visit count with them

**Top items**
- Most frequently taken service (by count of `AppointmentServiceLine` across this patient's appointments)
- Most frequently bought product (by count of `AppointmentProductLine`)

**Period spend**
- Spend in the last calendar month (`grandTotal` sum for `COMPLETED` appointments in the last 30 days, or current calendar month — see Open Question in §7)
- Spend in the last 12 months / current year (see same Open Question)

If the patient has zero appointments, show a friendly empty state ("No appointments yet for this patient") instead of blank/zero cards.

### 4.3 Section C — Appointment History (filterable list)

A table listing every appointment for this patient, most recent first, with columns:
- Date/time
- Therapist
- Services (comma-list or count, e.g. "Deep Tissue Massage +1 more")
- Products (comma-list or count)
- Grand total
- Payment status (`getPaymentStatus()`: PAID/PARTIAL/UNPAID/N/A)
- Status badge (SCHEDULED/COMPLETED/CANCELLED/NO_SHOW)
- **Actions:** View | Edit
  - **View** → `GET /appointments/{id}` (existing detail page — no changes needed)
  - **Edit** → `GET /appointments/{id}/edit` (existing edit page — only meaningful for `SCHEDULED` appointments per current business rules; keep the same guard rails already implemented in `AppointmentService.updateAppointment`)

**Filters** (form at the top of Section C, submitted as query params, defaulting to "all time / all statuses / all therapists"):
- Date range (from / to)
- Status (dropdown: All, Scheduled, Completed, Cancelled, No-show)
- Therapist (dropdown of therapists who have treated this patient, or the full active therapist list)

Filters only affect Section C's table. Section B's summary stats always reflect the patient's complete history, so the admin has a stable lifetime baseline regardless of how they're filtering the list below.

**Back navigation:** Since appointment View/Edit routes are shared with the global Appointments module, add a `returnUrl` (or `from=patient&patientId={id}`) query param so the appointment detail/edit pages can offer a "Back to Patient" link instead of (or alongside) "Back to Appointments". This mirrors the existing `returnUrl` pattern already used by `/appointments/{id}/complete`, `/cancel`, `/no-show`.

---

## 5. Data & Backend Notes

- **No new entities or columns required.** Everything needed already exists on `Patient` and `Appointment` (+ line items).
- `AppointmentRepository.findByPatientOrderByAppointmentDateTimeDesc(Patient)` already exists and returns the full unfiltered history — usable directly for Section B's aggregate calculations.
- For Section C's filtered table, extend `AppointmentService.findByFilters(...)` (currently takes `status, therapistId, dateFrom, dateTo, patientName`, built via `Specification`) to also accept a `patientId`, so the existing Specification-based filtering logic is reused rather than duplicated. Alternatively add a small dedicated method — implementer's judgment once in the code.
- New `PatientHistoryService` (or new methods on the existing `PatientService`) to compute Section B's aggregates. These are in-memory computations over the patient's appointment list (clinic-scale data volume does not warrant new SQL aggregate queries, but implementer may add repository-level `@Query` aggregates if simpler/cleaner — same style as the existing `sumRevenueByDateRange` in `AppointmentRepository`).
- `PatientController` gains one new route: `GET /patients/{id}` → `detail()` method, loading the patient, the unfiltered appointment list (for stats), and the filtered list (for the table) into the model.

---

## 6. Acceptance Criteria

1. Patient list page shows a **View** action per row that opens the new Patient Detail page.
2. Patient Detail page shows all patient fields (profile), including medical history/allergies/notes.
3. Summary stats section shows: total/completed/cancelled/no-show counts, lifetime revenue/paid/outstanding, last visit date, most-seen therapist, most frequent service, most frequent product, last-month spend, last-year spend — and these numbers do **not** change when the history filters below are applied.
4. Appointment history table lists every appointment for the patient by default, newest first, and updates correctly when filtered by date range, status, and/or therapist.
5. Each history row's **View** and **Edit** buttons navigate to the existing appointment detail/edit pages, correctly scoped to that appointment.
6. A patient with no appointments sees a clean empty state, not errors or blank/zero-filled cards that look broken.
7. No changes to existing Patient or Appointment CRUD behavior; Edit/Deactivate on the list page still work exactly as before.

---

## 7. Decided

- **"Last month" / "last year" spend** — **calendar-aligned**: current calendar month-to-date and current calendar year-to-date (matches the "monthly revenue" convention already used on the dashboard KPI cards in the core requirements doc, §6.4). Confirmed by clinic owner on July 4, 2026.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
