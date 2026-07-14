# Healing House Clinic — Actual Revenue Reporting

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 14, 2026
**Status:** Draft — ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Adds a new report page under `/reports` and relabels existing report figures — no changes to the Discounts, Combos, or Commission business rules themselves.

---

## 1. Problem Statement

The five existing report pages (`Daily`, `Period`, `Comparison`, `Patient Acquisition`, `Performance`) all present "Revenue" figures sourced from `Appointment.totalServiceAmount`/`totalProductAmount` and per-line `priceAtTime`/`lineTotal` (see `ReportAggregator.getPeriodSummary`, `ReportService.buildServicePerformance`/`buildProductPerformance`). These are **pre-discount, pre-combo, billed-regardless-of-collection** figures — correct as the base for therapist commission (per the core doc's Commission business rule, discounts never reduce commission), but they overstate what the clinic actually earned whenever a discount, combo, or unpaid balance is involved.

Example: a ₹1000 service with a 20% appointment discount shows as ₹1000 "Services Revenue" in every existing report, even though the patient was only charged ₹800 — and if they've only paid ₹500 so far, the clinic has ₹500 in hand, not ₹1000.

There is currently no page that answers "how much money did the clinic actually make/collect in this period," with filters to slice that by therapist, service, payment method, etc.

This document covers two changes:
1. **Relabeling** the existing reports so their figures are clearly marked as pre-discount/commission-base numbers, not real revenue (§4).
2. A **new "Actual Revenue" report** (`/reports/revenue`) showing real, post-discount, billed-vs-collected figures with filters (§5–§9).

---

## 2. Goals

- Nobody reading any report page mistakes a commission-base figure for real clinic income — existing labels are clarified with no change to their underlying numbers or calculations.
- A new report shows, for any selected date range and filter combination: how much was billed after all discounts, how much has actually been collected, how much is still outstanding, and how much discount was given.
- The report is filterable by date range, status, therapist, patient, service/product/tag, and payment method — matching the filter vocabulary already used elsewhere in the app (`AppointmentSpec`, patient/tag autocomplete).
- CSV and PDF export, consistent with every other report page.

### Non-goals (explicitly out of scope for this iteration)

- Any change to commission, bonus, or discount calculation logic — this is a reporting-only addition.
- Multi-location/branch reporting — this clinic is single-location.
- Accounts/ledger-grade accounting (e.g. tax handling, refund accounting beyond the existing wallet refund flow) — out of scope; this report reads existing fields, it doesn't introduce new financial concepts.
- Changing what counts as "revenue" for `DashboardService`'s KPI cards — those already read `grandTotal` correctly (per the core doc) and are unaffected.
- Previous-period comparison (Decided, §10) — deferred past v1 (§5.5).
- Per-item discount-rate breakdown, e.g. "this service is discounted on 40% of bookings" (Decided, §10) — deferred past v1; revisit once the base report is in use and a real need shows up.
- Implementation itself — this is a requirements document only.

---

## 3. Plain-Language Summary

Two honest numbers matter, and the new report shows both side by side:

1. **Billed amount** — what the patient owes after every discount (combo + whole-appointment) is applied. This is `Appointment.grandTotal`, which already exists and is already correct — it's just never been surfaced as its own report.
2. **Collected amount** — what the patient has actually paid so far (`Appointment.amountPaid`). Differs from billed whenever there's an outstanding balance (`getBalanceDue()`).

Only `COMPLETED` appointments count by default, matching the core doc's existing revenue-recognition rule ("a wallet-funded appointment counts as revenue at completion").

---

## 4. Relabeling Existing Reports

No calculation changes — labels and column headers only, so the underlying commission-base numbers stay exactly as-is for their intended purpose.

| Location | Current label | New label |
|---|---|---|
| `reports/daily.html` KPI card | "Services Revenue" | "Services Revenue (Pre-Discount)" |
| `reports/daily.html` KPI card | "Products Revenue" | "Products Revenue (Pre-Discount)" |
| `reports/daily.html`/`period.html`/`comparison.html` therapist table headers | "Services Rev.(All)", "Products Rev.(All)", "Products Rev.(Commission tagged)" | append "(Pre-Discount)" to each, e.g. "Services Rev.(All, Pre-Discount)" |
| `reports/performance.html` service/product/tag tables | "Revenue" columns | "Revenue (Pre-Discount)" |
| `reports/period.html`, `reports/performance.html` | page intro text | add a one-line note: "Figures below are pre-discount and used for commission calculation — see the [Actual Revenue](/reports/revenue) report for real billed/collected amounts." with a link to the new report |
| `reports/index.html` card grid | Daily/Period card descriptions mentioning "Revenue" | no wording change needed (descriptions already say "earnings", not "revenue") but add a new sixth card for the new report (§7) |

A small info-icon tooltip (Bootstrap `data-bs-toggle="tooltip"`, already used elsewhere in the app) next to each relabeled figure, explaining: "Calculated before any discount — this is the base used for therapist commission, not the amount actually charged." avoids cluttering the header text further while still being discoverable.

CSV/PDF export column headers (`CsvExportUtil`/`PdfExportUtil` calls in `ReportController`) get the same suffix so exported files are self-explanatory outside the app too.

---

## 5. New Report: Actual Revenue

### 5.1 Route & Navigation

- `GET /reports/revenue` — HTML view, same date-range-default-last-30-days convention as other reports.
- `GET /reports/revenue/export-csv`, `GET /reports/revenue/export-pdf` — same pattern as every other report in `ReportController`.
- New sixth card on `reports/index.html`: "Actual Revenue | /reports/revenue | bi-cash-coin | Real billed and collected revenue after discounts, with filters."

### 5.2 Filters

All optional; sensible defaults noted.

| Filter | Default | Notes |
|---|---|---|
| Date range (`dateFrom`/`dateTo`) | last 30 days | same as existing reports |
| Status | `COMPLETED` only | multi-select checkbox group (`SCHEDULED`/`COMPLETED`/`CANCELLED`/`NO_SHOW`); only `COMPLETED` counts as real revenue but staff may want to see `SCHEDULED` as "expected" — see §6 |
| Therapist | all | reuses `AppointmentSpec.hasTherapistId` semantics — main therapist OR any reassigned line therapist |
| Patient | all | typeahead reusing the existing `GET /patients/search` autocomplete already wired into `appointments/list.html` |
| Service / Product / Tag | all | reuses `TagController`'s `GET /tags/search` autocomplete pattern; matches if any line on the appointment carries the selected service/product/tag |
| Payment method | all | `CASH`/`UPI`/`BANK_TRANSFER`/`CARD`/`OTHER`, plus a synthetic "Wallet" bucket (§5.4) |
| Discount presence | all | optional toggle: "Discounted only" (`isDiscounted()` true or `getTotalComboDiscount()` > 0) |

### 5.3 Summary Cards (top of page)

| Card | Source |
|---|---|
| Gross Revenue (Pre-Discount) | Σ `totalServiceAmount + totalProductAmount` across matched appointments — shown for contrast with Net Revenue |
| Combo Discounts Given | Σ `getTotalComboDiscount()` |
| Manual Discounts Given | Σ `discountAmount` |
| **Net Revenue (Billed)** | Σ `grandTotal` — the headline figure |
| Collected | Σ `amountPaid` |
| Outstanding | Σ `getBalanceDue()` |
| Wallet-Funded Portion | Σ `walletAmountApplied` — broken out since it's a liability shift (prepaid balance being drawn down), not new cash in the door |

Note: `Net Revenue = Gross Revenue − Combo Discounts − Manual Discounts`, and `Net Revenue = Collected + Outstanding` — both identities should hold exactly and are a good basis for a unit test once implemented.

### 5.4 Breakdown Tables

- **By payment method** — Σ `amountPaid` grouped by `paymentMethod`, with wallet-funded amounts (`walletAmountApplied`) shown as their own row rather than folded into whichever `paymentMethod` was recorded for the remaining cash portion (avoids double-counting a wallet top-up as revenue twice — once when topped up, once when applied).
- **By therapist** — per therapist: Gross, Discounts, Net Revenue, Collected, Outstanding for appointments where they're involved (main or line-level), attributed the same way `AppointmentSpec.hasTherapistId` already does. Explicitly labeled as distinct from the Comparison report's commission figures (different base).
- **By service/product/tag** — Net Revenue (post all discounts) per catalog item, contrasting with `performance.html`'s pre-discount figures.
- **Daily/period trend chart** — Net Revenue per day across the selected range, reusing the Chart.js pattern from `DashboardService`'s 7-day trend.
- **Appointment-level table** (paginated, like other list pages) — date, patient, therapist, status, gross, discount, net, collected, outstanding, payment method — the drill-down row backing every summary number above, and the source rows for CSV/PDF export.

### 5.5 Comparison to previous period — deferred (Decided, §10)

Not built in v1. A "% vs previous period of equal length" delta on the Net Revenue and Collected cards is a reasonable future addition, but it's deferred until the base report is in use.

---

## 6. Business Rules for This Report

- **Status scope (Decided, §10):** appointments included by default are `COMPLETED` only — matches the core doc's existing wallet business rule ("a wallet-funded appointment counts as revenue at completion"). If staff select other statuses via the filter, cancelled/no-show appointments are included in the drill-down table for visibility (e.g. "how much would we have lost to no-shows this month") but are **never counted into the summary cards' Net Revenue/Collected/Outstanding totals** — those always stay `COMPLETED`-only regardless of which statuses are checked in the filter, so the headline numbers can't be silently inflated by including non-revenue statuses.
- **No changes to `Appointment`, line items, `AppointmentCombo`, or any `Service`-layer discount/commission logic.** This report is purely additive — new query/aggregation methods reading existing fields, no schema change.
- **Therapist attribution** for the "by therapist" breakdown uses the same "main OR any line" definition already established by `AppointmentSpec.hasTherapistId`/`getOtherLineTherapists()`, so a therapist who only handled one reassigned line on someone else's appointment still shows up here, consistent with how they already appear in the Comparison report and their own detail page.
- **Wallet top-ups/refunds are not revenue** — only `grandTotal` (billed) and `amountPaid` (collected) matter for this report; a patient topping up their wallet balance doesn't appear here at all (it's a separate `WalletTransaction` ledger entry, not tied to any appointment's revenue), only when that balance is later *applied* to an appointment's `walletAmountApplied`.

---

## 7. Service / Controller Changes (indicative — not binding on the implementer)

### 7.1 New: `RevenueReportDTO`, `RevenueSummaryDTO`, `RevenueByTherapistDTO`, `RevenueByPaymentMethodDTO`

Follow the existing DTO-per-report-section convention (`PeriodSummaryDTO`, `TherapistEarningsDTO`, etc.) rather than one monolithic DTO.

### 7.2 `ReportAggregator` or a new `RevenueReportAggregator`

- `getRevenueSummary(filters)` — single query/aggregation pass over matched appointments producing §5.3's card figures.
- Reuses `AppointmentSpec` predicates (`hasStatus`, `hasTherapistId`, `hasPatientId`, `betweenDates`) combined with new predicates for service/product/tag filtering (mirroring how `AppointmentServiceLineRepository`/`AppointmentProductLineRepository` already join on `tags` for commission filtering) and payment method.

### 7.3 `ReportService`

- `getRevenueReport(RevenueReportFilter filter)` — new method alongside the existing five `get*Report` methods, same signature style (date range + optional filter fields).

### 7.4 `ReportController`

- `GET /reports/revenue`, `GET /reports/revenue/export-csv`, `GET /reports/revenue/export-pdf` — same three-endpoint pattern as every existing report.

### 7.5 `CsvExportUtil` / `PdfExportUtil`

- New `writeRevenueReportCsv(...)` / `generateRevenueReportPdf(...)` methods, following the existing per-report method-per-export convention; PDF export reuses `PdfExportUtil`'s existing letterhead/footer/page-numbering infrastructure (`FooterEventHandler`, `initFontsForDocument()`/`finish()` pairing) — no changes needed there, just a new document-building method.

---

## 8. UI / Template Changes

- New `templates/reports/revenue.html` — filter panel (date range, status checkboxes, therapist/patient typeahead, service/product/tag typeahead, payment method, discount-only toggle) + summary cards (§5.3) + trend chart + breakdown tables (§5.4) + paginated appointment drill-down table, following the layout conventions already established in `period.html`/`performance.html`.
- `templates/reports/index.html` — add the sixth card (§5.1).
- `templates/reports/daily.html`, `period.html`, `comparison.html`, `performance.html` — label/tooltip changes only (§4).

---

## 9. Acceptance Criteria

1. Existing report pages (Daily, Period, Comparison, Performance) show "(Pre-Discount)" (or equivalent) on every revenue figure that is pre-discount/commission-base, with no change to the underlying numbers.
2. A new `/reports/revenue` page shows Gross Revenue, Combo Discounts Given, Manual Discounts Given, Net Revenue, Collected, Outstanding, and Wallet-Funded Portion for a selected date range.
3. `Net Revenue = Gross Revenue − Combo Discounts − Manual Discounts` and `Net Revenue = Collected + Outstanding` hold exactly for any filter combination.
4. The report can be filtered by date range, status, therapist, patient, service/product/tag, and payment method; each filter narrows the summary cards, breakdown tables, and drill-down table consistently.
5. By default only `COMPLETED` appointments count toward the headline totals; other statuses are visible only if explicitly included via the status filter, and are called out separately rather than summed into Net Revenue/Collected.
6. Breakdown tables by payment method, therapist, and service/product/tag are present and each figure is traceable to the appointment-level drill-down table.
7. CSV and PDF export work and match the on-screen figures, following the existing export conventions (branded PDF letterhead/footer, opencsv-based CSV).
8. No changes to any commission, bonus, discount, or combo calculation — verified by confirming existing reports' underlying numbers are unchanged after the relabeling in §4.
9. No schema changes — the report is built entirely from existing `Appointment`/`AppointmentCombo`/line-item fields.

---

## 10. Decided (Open Questions Resolved)

All confirmed by clinic owner, July 14, 2026:

- **Cancelled/no-show appointments:** shown in the drill-down table for visibility (e.g. spotting lost revenue from no-shows) but never counted into the summary cards' totals, which stay `COMPLETED`-only regardless of the status filter (§6).
- **Previous-period comparison:** deferred past v1 — not built now, can be added later once the base report is in use (§5.5).
- **Per-item discount-rate breakdown:** deferred past v1 — revisit only if a real need shows up once the report is live (§5.4, §2 non-goals).

---

*Document Version 1.0 — Healing House Clinic — July 2026*
