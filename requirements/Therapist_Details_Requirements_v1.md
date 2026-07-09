# Healing House Clinic — Therapist Details & History View
## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 9, 2026
**Status:** Approved — ready for implementation, all open questions resolved
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Mirrors the existing **Patient History** feature (`Patient_History_Requirements_v1.md`), applied to the **Therapist** module. Builds on Therapist (Phase 1, done), Appointment + per-line therapist assignment (Phase 2 + `Per_Line_Therapist_Assignment_Requirements_v1.md`, done), and Commission/Bonus calculation (Phase 3, done).

---

## 1. Problem Statement

Today there is no way to answer "What has this therapist done, and what have they earned?" from one place. The `therapists/list` page only offers **Edit** and **Deactivate**. A therapist's work is spread across appointments they're the main therapist on, appointments where they only handled a reassigned line item, and the commission/bonus figures already computed by `CommissionCalculator` — but the admin has to cross-reference the Comparison report and the global Appointments list (typing the therapist's name each time) to piece it together.

This document defines a **Therapist Detail page** that consolidates a therapist's profile, period earnings summary, and full appointment history (with filters) in one place.

---

## 2. Goals

- Add a **View** button next to Edit/Deactivate on the therapist list, opening a dedicated Therapist Detail page.
- Show **all therapist details** already captured (specialization, contact info, salary, commission rate, bonus threshold/amount, notes, active status).
- Show a **period earnings summary** — revenue, commission, bonus, and appointment activity for a selected date range — reusing the existing `CommissionCalculator` so the numbers always match the Reports module.
- Show the **full appointment history** as a filterable list below the summary, covering appointments where this therapist is either the **main therapist or a reassigned line-item therapist**.
- Each history row's **View** and **Edit** buttons reuse the existing appointment detail/edit pages (`/appointments/{id}` and `/appointments/{id}/edit`), not new pages.

### Non-goals (explicitly out of scope for this iteration)
- CSV export / print-friendly view of therapist history (deferred — same pattern as Phase 3 reports CSV export, can be added later).
- Editing salary/commission/bonus fields inline from this page (still done via the existing Edit Therapist form).
- Multi-therapist comparison (that's already covered by the existing Comparison report; this page is single-therapist only).
- Payroll/payout workflow (marking a payout as "paid") — this page is read-only reporting, same as the rest of the Reports module.

---

## 3. User Entry Point

**Therapist List (`therapists/list.html`)**
- Add a **View** button/icon per row, placed before Edit (matches the Patient list's View → Edit → Deactivate order). Links to `GET /therapists/{id}`.
- Existing **Edit** (`/therapists/{id}/edit`) and **Deactivate** (`POST /therapists/{id}/delete`) buttons stay as-is.

---

## 4. Therapist Detail Page — Layout

`GET /therapists/{id}` → new template `therapists/detail.html`, using the standard `fragments/layout.html` (`navbar('Therapists')`).

Page is composed of three stacked sections. Sections B and C share **one** date-range control (see below) — unlike Patient History, where lifetime stats and history filters are independent, therapist earnings are inherently period-based, so keeping both sections in sync avoids showing a commission figure for one period next to a history table for another.

### 4.1 Section A — Therapist Profile Card

All fields from the `Therapist` entity, formatted for reading (not a form):
- Full name, specialization, phone, email
- **Owner** badge instead of salary/commission fields if `therapist.isOwner()` is true (currently only Marcia Gomes Yadav) — her salary/commission/bonus fields are 0/unused, showing them as normal figures would be misleading
- Fixed monthly salary, commission rate (as %), performance bonus threshold, performance bonus amount (hidden/replaced by the Owner badge per above)
- Notes (empty shows "—")
- Active/Inactive badge
- Therapist since (`createdAt`)
- An **Edit** button linking to `/therapists/{id}/edit`

### 4.2 Section B — Earnings Summary (for the selected date range)

Driven by the shared date-range control described in §4.4. Computed via `CommissionCalculator.calculateEarnings(therapist, dateFrom, dateTo)` — the same method and DTO (`TherapistEarningsDTO`) already used by the Reports module, so figures here are always consistent with the Period/Comparison reports.

Presented as KPI cards / a summary table:

**Activity**
- Total appointments in period (main therapist **or** line-item therapist, i.e. `AppointmentSpec.hasTherapistId` semantics)
- Completed count (of the above)
- Services performed — all vs. Bonus-tagged count (`allServicesCount` vs. `servicesCount`)

**Revenue & earnings** (mirrors `templates/reports/period.html`'s per-therapist rendering)
- Services revenue (All) / Products revenue (All) — `allServicesRevenue`, `allProductsRevenue`
- Services revenue (Commission-tagged) / Products revenue (Commission-tagged) — `servicesRevenue`, `productsRevenue`
- Total commission — `totalCommission`
- Bonus earned (yes/no) + amount — `bonusEarned`, `bonusAmount`
- Total variable pay — `totalVariablePay`
- Fixed monthly salary (shown for reference; not prorated to the selected range)

**If `therapist.isOwner()`** — skip the commission/bonus figures entirely and show only the activity counts, with a note ("Owner — no commission or bonus applies").

If the therapist has zero appointments in the selected range, show a friendly empty state ("No activity for this therapist in the selected period") instead of blank/zero cards.

### 4.3 Section C — Appointment History (filterable list)

A table listing appointments for this therapist **within the selected date range**, most recent first, where the therapist is the main therapist and/or handled at least one line item. Columns:
- Date/time
- Patient
- Role — badge indicating whether this therapist is the appointment's main therapist, or was only assigned to specific line item(s) while someone else is the main therapist (reuse/mirror the existing `Appointment.getOtherLineTherapists()` helper, inverted: flag when `appointment.therapist.id != this therapist's id`)
- Services (comma-list or count)
- Products (comma-list or count)
- Grand total
- Payment status (`getPaymentStatus()`: PAID/PARTIAL/UNPAID/N/A)
- Status badge (SCHEDULED/COMPLETED/CANCELLED/NO_SHOW)
- **Actions:** View | Edit
  - **View** → `GET /appointments/{id}` (existing detail page — no changes needed)
  - **Edit** → `GET /appointments/{id}/edit` (existing edit page, same guard rails as today — only meaningful for `SCHEDULED` appointments)

**Filters** (form at the top of the page, submitted as GET query params):
- **Date range** (from / to) — shared with Section B, defaults per §4.4
- **Status** (dropdown: All, Scheduled, Completed, Cancelled, No-show)
- **Patient name** (free-text search)

**Back navigation:** add a `returnUrl` (or `from=therapist&therapistId={id}`) query param so the appointment detail/edit pages can offer a "Back to Therapist" link — same existing pattern used by Patient History and by `/appointments/{id}/complete`, `/cancel`, `/no-show`.

### 4.4 Shared Date Range Control

One date-range picker (from / to) at the top of the page drives **both** Section B's earnings summary and Section C's history table.
- **Default on first load:** current calendar month (1st of the current month through today), matching the clinic's monthly commission/bonus payout cycle.
- Changing the range and resubmitting re-runs both the earnings calculation and the history query.

---

## 5. Data & Backend Notes

- **No new entities or columns required.** Everything needed already exists on `Therapist`, `Appointment`, `AppointmentServiceLine`/`AppointmentProductLine`, and `CommissionCalculator`.
- **Section B:** call `CommissionCalculator.calculateEarnings(therapist, dateFrom, dateTo)` directly for the one subject therapist — no changes needed to `CommissionCalculator` itself. Activity counts (total appointments, completed count) for the period can be derived from the same filtered appointment list used for Section C, avoiding a duplicate query.
- **Section C:** reuse `AppointmentService.findByFilters(status, therapistId, dateFrom, dateTo, patientName, patientId)` — pass this therapist's id as `therapistId` (which already matches main-OR-line-item via `AppointmentSpec.hasTherapistId`) and leave `patientId` null, using the existing `patientName`/`status`/date params for the page's own filters. No backend changes needed here; this method already supports everything this page requires.
- `TherapistController` gains one new route: `GET /therapists/{id}` → `detail()` method, loading the therapist, resolving the effective date range (query params or current-month default), calling `CommissionCalculator.calculateEarnings` for Section B, and `AppointmentService.findByFilters` for Section C, into the model. Follow the same try/catch-redirect-with-flash-error pattern as `PatientController.detail()` for a missing therapist id.
- New template `therapists/detail.html`, structurally cloned from `templates/patients/detail.html` (profile card → summary cards → filterable history table), reusing the earnings-table rendering conventions from `templates/reports/period.html` for the commission/bonus figures, and the same `#numbers.formatDecimal`/`#temporals.format`/badge conventions used throughout.

---

## 6. Acceptance Criteria

1. Therapist list page shows a **View** action per row that opens the new Therapist Detail page.
2. Therapist Detail page shows all therapist fields (profile), with an Owner badge (and salary/commission fields hidden) for the owner.
3. Earnings summary section shows activity counts and, for non-owner therapists, revenue/commission/bonus/variable-pay figures for the selected date range, matching what the Period/Comparison reports would show for the same therapist and range.
4. Owner (Marcia Gomes Yadav) sees activity counts only, no commission/bonus figures, with an explanatory note — no broken zero-filled cards.
5. Appointment history table lists every appointment where this therapist is the main therapist or handled a line item, within the selected date range, newest first, and correctly flags line-item-only rows.
6. Changing the date range updates both the earnings summary and the history table together.
7. History table further filters correctly by status and/or patient name within the selected date range.
8. Each history row's **View** and **Edit** buttons navigate to the existing appointment detail/edit pages, correctly scoped to that appointment.
9. A therapist with no activity in the selected range sees a clean empty state, not errors or blank/zero-filled cards that look broken.
10. No changes to existing Therapist or Appointment CRUD behavior; Edit/Deactivate on the therapist list still work exactly as before.

---

## 7. Decided

- **Appointment history scope** — includes both appointments where the therapist is the main therapist and appointments where they only handled a reassigned line item (reuses `AppointmentSpec.hasTherapistId` as-is). Decided July 9, 2026.
- **Summary content** — earnings-focused (revenue/commission/bonus via `CommissionCalculator`), plus minimal activity counts for context. Decided July 9, 2026.
- **History filters** — date range + status + patient name. Decided July 9, 2026.
- **Date range scope** — one shared date-range control drives both the earnings summary and the history table (not independent filters). Decided July 9, 2026.
- **Default date range** — current calendar month, matching the monthly commission/bonus payout cycle. Decided July 9, 2026.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
