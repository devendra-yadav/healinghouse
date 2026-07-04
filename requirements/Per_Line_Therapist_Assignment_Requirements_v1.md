# Healing House Clinic — Per-Line Therapist Assignment

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 5, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Modifies the **Appointment** module (Phase 2, done) and affects the not-yet-built **Reports & Therapist Earnings** module (Phase 3). Also affects `Patient_History_Requirements_v1.md` (Phase 2.5, done). Should be implemented as a dedicated phase before Phase 3, since Phase 3's commission logic depends on the outcome of this change.

---

## 1. Problem Statement

Today, an `Appointment` has exactly one `therapist` (a required, non-nullable field on `Appointment`). That single therapist is implicitly treated as the person who performed every service and sold every product on the appointment.

In practice, a single appointment can involve more than one staff member — e.g. Therapist A does the massage while Therapist B sells/administers a retail product, or two different therapists assist with two different services in the same visit. There is currently no way to represent this: `AppointmentServiceLine` and `AppointmentProductLine` have no therapist reference at all, and every therapist-scoped query (revenue, commission, filters, "most-seen therapist") joins through `Appointment.therapist`.

This document defines how to attach a therapist to each service line and product line individually, defaulting to the appointment's main therapist but overridable per line, and traces every downstream piece of the system that assumes "one therapist per appointment."

---

## 2. Goals

- Keep a single **primary/main therapist** field on the appointment (unchanged — still required, still selected first).
- Allow choosing a **therapist per service line** and **a therapist per product line**, defaulting to the appointment's main therapist.
- Allow overriding that default independently for any individual line, for both new and existing (edit) appointments.
- Any **active therapist** may be assigned to any line — there is no therapist-to-service capability restriction in this iteration (no such mapping exists in the system today).
- Propagate the change everywhere therapist attribution currently matters: appointment detail/edit views, patient appointment history, therapist-scoped filters, and the (not-yet-built) commission/revenue reporting logic.
- Commission and revenue-by-therapist calculations must always reflect the **current** per-line therapist assignment — i.e. computed live from whatever the line's therapist is *right now*, not frozen at the moment the appointment was completed. (This is consistent with the existing price-snapshot design only insofar as *price* is frozen at booking time — therapist attribution is explicitly **not** frozen and can be corrected retroactively by editing the appointment, even after completion.)

### Non-goals (explicitly out of scope for this iteration)

- Therapist-to-service/product capability mapping (e.g. "only Therapist X can perform Deep Tissue Massage") — any active therapist is selectable for any line.
- Splitting a single line's revenue/commission across multiple therapists (one line = one therapist, no co-therapist split).
- Changing how stock deduction works (still keyed off `Appointment.status == COMPLETED`, unaffected by which therapist is on the product line).
- Building the full Phase 3 dashboard/reports UI — this doc only defines how the underlying data model and repository queries must behave so that Phase 3 can be built correctly on top of it.
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 `AppointmentServiceLine`

Add a new field:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "therapist_id", nullable = false)
private Therapist therapist;
```

- Populated at save time: if the form doesn't specify a line-level therapist, resolve it to the appointment's main therapist (see §5.2). The column itself is `NOT NULL` — every line always has a resolved therapist, there is no "unset" state once persisted.

### 3.2 `AppointmentProductLine`

Same addition, same semantics:

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "therapist_id", nullable = false)
private Therapist therapist;
```

### 3.3 `Appointment.therapist` (unchanged)

Stays as-is: required, non-nullable, represents the **main/primary therapist** for the visit. Used as:
1. The default value pre-filled into every new line's therapist selector.
2. The therapist shown in list views / summary contexts where a single therapist per appointment is still the most useful representation (e.g. the Appointments list table).

### 3.4 ER Diagram Update

Add to the Mermaid diagram in `Healing_House_Clinic_Requirements_v1.md` §5:

```
THERAPIST ||--o{ APPOINTMENT_SERVICE_LINE : "performs"
THERAPIST ||--o{ APPOINTMENT_PRODUCT_LINE : "sells"
```

(In addition to the existing `THERAPIST ||--o{ APPOINTMENT : "handles"`.)

### 3.5 Existing Data / Rollout

`hibernate.ddl-auto: update` will auto-add the new `therapist_id` columns to `appointment_service` and `appointment_product` on next startup. Since the column is `NOT NULL`, existing rows must be backfilled in the same deploy:

- **Backfill rule:** for every existing `AppointmentServiceLine`/`AppointmentProductLine`, set `therapist_id = appointment.therapist_id` (i.e. every historical line inherits its parent appointment's main therapist — a safe, lossless default since that was the only therapist information that existed before this change).
- This can be a one-off `UPDATE ... JOIN` SQL statement run once, or handled via a Hibernate-compatible migration step at startup (implementer's judgment) — but it must run **before** the `NOT NULL` constraint is enforced, or the schema update will fail on non-empty tables.

---

## 4. DTO Changes

### 4.1 `AppointmentForm.ServiceLineForm`

```java
private Long serviceId;
private Integer quantity;
private Long therapistId;   // NEW — nullable in the form; null means "use appointment's main therapist"
```

### 4.2 `AppointmentForm.ProductLineForm`

```java
private Long productId;
private Integer quantity;
private Long therapistId;   // NEW — same semantics
```

### 4.3 `AppointmentForm.from(Appointment appt)`

Update the edit-mode factory to copy each line's persisted `therapist.getId()` into `ServiceLineForm.therapistId` / `ProductLineForm.therapistId`, so the edit form pre-selects whichever therapist is actually stored on that line (not necessarily the main therapist, since it may have been overridden).

---

## 5. Business Rules

### 5.1 Selecting the main therapist (unchanged)

The appointment-level `therapistId` dropdown remains required and is chosen first, exactly as today.

### 5.2 Default-then-override behavior for lines

- When a new service or product row is added in the UI, its therapist selector **defaults to whatever is currently chosen as the appointment's main therapist**.
- If the admin changes the main therapist dropdown *after* rows already exist, any line whose therapist selector has **not been manually touched** should update to follow the new main therapist (JS-driven convenience — matches typical "the main therapist changed, and I haven't customized this row" expectation). Once a line's therapist selector has been explicitly changed by the admin, it stops following the main therapist dropdown for the rest of that session.
- Any line's therapist selector may be changed independently to any other **active** therapist, regardless of what the main therapist is.
- On submit, every line must resolve to a concrete `therapistId` — if a row's selector was left blank for any reason, fall back to the appointment's main therapist server-side as a safety net (the form should not allow a truly empty state to reach the service layer).

### 5.3 Editing existing appointments

- The edit form pre-populates each line's therapist selector from the persisted value (not recomputed from the current main therapist), so previously-customized assignments are preserved when reopening an appointment for edits.
- Changing a line's therapist on an already-`COMPLETED` appointment is allowed (per the "always recalc live" decision in §2) and immediately affects future commission/revenue calculations for both the old and new therapist. This does **not** touch `priceAtTime` or stock — only therapist attribution changes.

### 5.4 Therapist eligibility

Any therapist with `active = true` is selectable for any line (main therapist dropdown, and every per-line override). No filtering by specialization or a therapist-service capability list, since no such mapping exists in the system.

---

## 6. UI / Template Changes

### 6.1 `templates/appointments/form.html` (create + edit)

- **Service rows** (`addServiceRow`): add a therapist `<select class="line-therapist-select" name="serviceLines[i].therapistId">` next to the existing service/quantity fields, populated from the same active-therapist list already loaded for the main dropdown. Preselect it with the appointment's current main therapist for new rows, or the line's persisted therapist for edit-mode rows.
- **Product rows** (`addProductRow`): identical addition — `productLines[i].therapistId` select.
- Inject a `THERAPIST_DATA` JS array (id + name), mirroring the existing `SERVICE_DATA`/`PRODUCT_DATA` pattern, so row-building JS doesn't need a server round-trip to populate the new selects.
- Main therapist dropdown `onchange` handler: for every row whose therapist select has not been manually overridden (track this with a data attribute, e.g. `data-follows-main="true"`, cleared the moment the admin touches that specific select), update its selected value to match the new main therapist.
- Submit-time re-indexing logic (the loop that currently renumbers `serviceLines[si].serviceId` / `.quantity`) must also renumber `.therapistId` for each row so indices stay aligned after rows are added/removed.
- Edit-mode bootstrap (`EDIT_SERVICE_LINES` / `EDIT_PRODUCT_LINES` JS arrays, built server-side in `AppointmentController.editForm()`): add `therapistId` to each map entry, and pass it as a third argument into `addServiceRow(serviceId, qty, therapistId)` / `addProductRow(productId, qty, therapistId)`.

### 6.2 `templates/appointments/detail.html`

- Add a **Therapist** column to both the Services table and the Products table, showing each line's `therapist.fullName`. When a line's therapist matches the appointment's main therapist, it may optionally be styled the same as any other value (no special-casing needed) — but if a line's therapist *differs* from the main therapist, consider a small visual indicator (e.g. a badge or icon) so admins can spot overrides at a glance.
- The existing single "Therapist" row in the Appointment Details card stays as-is (still represents the main therapist).

### 6.3 `templates/appointments/list.html`

- No structural change to the table (still shows the main therapist per appointment row).
- Decide whether the "filter by therapist" dropdown should match appointments where the therapist appears as the **main therapist OR on any line**. Recommended: broaden the filter to match either, since an admin looking up "what did Therapist X do this week" should see appointments where X only handled a line item, not just ones where X was the main therapist. This requires extending `AppointmentSpec.hasTherapistId` (or adding a new spec) to also check `serviceLines.therapist` / `productLines.therapist` via a join.

---

## 7. Patient Appointment History Impact (`Patient_History_Requirements_v1.md`)

### 7.1 Section B — "Most-seen therapist" stat

Currently counted once per appointment via `appointment.getTherapist()`. Recommended change: count **per line item** (each service line and product line contributes one count for its own therapist), so a therapist who frequently assists on lines — even without ever being the main therapist on an appointment — is correctly reflected as "most-seen." This is a more accurate measure of "who has actually treated this patient the most," which is the stat's stated purpose.

### 7.2 Section C — History table "Therapist" column

A single "Therapist" column showing the appointment's main therapist becomes ambiguous once lines can have different therapists. Recommended change: keep the column showing the **main therapist** (consistent with the rest of the list-style views in §6.3), but make it visually indicate when any line on that appointment used a different therapist (e.g. "Dr. A +1 other" or a tooltip listing all distinct therapists involved), with the full per-line breakdown available on the appointment's own detail page (§6.2).

### 7.3 Therapist filter dropdown (Section C)

Same recommendation as §6.3 — broaden to match main therapist OR any line therapist, reusing whatever spec change is made there.

---

## 8. Commission & Revenue Reporting (Phase 3 — not yet built)

Since no `ReportService`/`DashboardService` exists yet and the three relevant repository queries are currently unused, this feature should be designed correctly from the start rather than retrofitted later.

### 8.1 Repository query changes

`AppointmentServiceLineRepository` and `AppointmentProductLineRepository`'s therapist-scoped aggregate queries must join on the **line's own therapist**, not the parent appointment's:

```java
// Before (appointment-level):
WHERE sl.appointment.therapist = :therapist AND sl.appointment.status = 'COMPLETED' ...

// After (line-level):
WHERE sl.therapist = :therapist AND sl.appointment.status = 'COMPLETED' ...
```

Applies to:
- `AppointmentServiceLineRepository.sumServiceRevenueByTherapistAndDateRange`
- `AppointmentServiceLineRepository.countServicesPerformedByTherapistAndDateRange`
- `AppointmentProductLineRepository.sumProductRevenueByTherapistAndDateRange`

`AppointmentRepository.findByTherapistOrderByAppointmentDateTimeDesc` / `findByTherapistAndDateRange` (appointment-level, used for "this therapist's appointments" views) remain appointment-scoped by design — they answer "which appointments did this therapist headline," which is a different question from "which lines did this therapist personally perform."

### 8.2 Commission formula (update to `Healing_House_Clinic_Requirements_v1.md` §6.3)

The formula itself doesn't change shape, only what "servicesRevenue" and "productsRevenue" mean — they now come from the line-level sums above rather than an appointment-level sum:

```java
BigDecimal servicesRevenue = sumServiceRevenueByTherapistAndDateRange(therapist, start, end);   // now line-scoped
BigDecimal productsRevenue = sumProductRevenueByTherapistAndDateRange(therapist, start, end);    // now line-scoped
BigDecimal commission = (servicesRevenue.add(productsRevenue)).multiply(therapist.getCommissionRate());

long servicesCount = countServicesPerformedByTherapistAndDateRange(therapist, start, end);       // now line-scoped
BigDecimal bonus = servicesCount >= therapist.getPerformanceBonusThreshold()
    ? therapist.getPerformanceBonusAmount() : BigDecimal.ZERO;

BigDecimal totalVariablePay = commission.add(bonus);
```

Marcia Gomes Yadav (owner, `commissionRate = 0`, `fixedMonthlySalary = 0`) is still skipped from payout calculations, unchanged.

**Consequence of "always recalc live":** because the queries run against current `therapist_id` values on each line, a therapist's commission for a past period will change if an admin later edits which therapist is attributed to a line within that period — there is no locked/frozen commission snapshot per pay period in this system today, and this feature does not introduce one. If period-close locking is ever needed, it would be a separate future requirement.

### 8.3 Dashboard KPI cards

Unaffected structurally — clinic-wide revenue (`sumRevenueByDateRange`) is not therapist-scoped and continues to sum `appointment.grandTotal` regardless of per-line therapist attribution.

---

## 9. Acceptance Criteria

1. Creating a new appointment lets the admin pick a main therapist, then add service/product lines each with their own therapist selector, defaulting to the main therapist.
2. Changing the main therapist dropdown updates any line therapist selector that hasn't been manually overridden; manually-changed line selectors are left alone.
3. Submitting the form persists the correct therapist on every `AppointmentServiceLine` and `AppointmentProductLine`, independent of the appointment's main therapist.
4. Editing an existing appointment shows each line's actual persisted therapist (not recomputed from the current main therapist), and allows changing it, including on `COMPLETED` appointments.
5. The appointment detail page shows a therapist column on both the services and products tables, reflecting each line's real therapist.
6. The patient history page's "most-seen therapist" stat and history table reflect line-level therapist data, not just the main therapist.
7. Therapist-scoped commission/revenue repository queries (once wired into Phase 3 reporting) aggregate by each line's own therapist, and recalculate correctly if a line's therapist is edited after the appointment is completed.
8. Existing appointments continue to work after rollout: their historical lines are backfilled with the appointment's main therapist as the line therapist, with no manual data-entry required.
9. No regressions to stock deduction (still triggered by appointment status becoming `COMPLETED`, unaffected by therapist assignment) or price snapshotting (`priceAtTime` unaffected by therapist changes).

---

## 10. Decided (Open Questions Resolved)

- **Recalculation scope:** Commission/revenue always recalculates live from the current per-line therapist assignment, even for completed/past appointments — there is no locked commission snapshot. Confirmed by clinic owner, July 5, 2026.
- **Therapist eligibility per line:** Any active therapist may be assigned to any service or product line; there is no therapist-to-service capability restriction in this system. Confirmed by clinic owner, July 5, 2026.
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
