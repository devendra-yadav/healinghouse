# Bug Report — Full Application Review (v1)

**Date:** 2026-07-14
**Method:** Static code review (4 parallel focused passes covering appointment/discount/combo/wallet logic, commission/reporting, master-data services/controllers, and Thymeleaf templates/JS) + live testing against the running dev instance (`http://localhost:8080`, MySQL `healing_house_clinic`) via direct HTTP requests.
**Scope:** No source code was modified. Some test records (patients' wallet top-ups, a handful of test appointments) were created in the dev DB while probing live behavior — see "Test data created" at the end.

Findings are ordered by severity. Each includes file:line, the bug, a concrete failure scenario, and confidence (**CONFIRMED** = traced code and/or reproduced live; **PLAUSIBLE** = reasoned from code, not reproduced).

---

## Critical

### 1. Reassigning an appointment's patient during edit leaks/misattributes wallet funds
**Files:** `AppointmentService.java` (`updateAppointment`, patient reassignment ~line 555; wallet delta ~650-676; `reverseFullWalletIfAny` ~893-901); `appointments/form.html` (patient `<select>` not disabled in edit mode)
**Confidence: CONFIRMED — reproduced live**

`updateAppointment` lets the patient on an existing appointment be changed with no restriction, and the form's patient dropdown is fully editable in edit mode. The wallet delta/reversal logic keys off `existing.getPatient()`, i.e. the **new** patient, not whoever the money was actually drawn from.

**Reproduced:** Topped up Patient 2's wallet to ₹1000, created Appointment #10 for Patient 2 applying ₹400 from wallet (balance → ₹600). Edited Appointment #10, changing only `patientId` to Patient 3, resubmitting the same `walletAmountApplied=400`. Result:
- Patient 2's wallet stayed at ₹600 (permanently debited, money never coming back — the appointment no longer references them at all).
- Patient 3's wallet stayed at ₹0 (never debited).
- Appointment #10 now displays Patient 3 as the owner with "Paid from Wallet ₹400.00" — a payment Patient 3 never made.

This is a real, silent money-leak: reassigning an appointment fabricates a wallet payment for the new patient while stranding the old patient's debited balance with no route back.

---

## High

### 2. Division-by-zero crash in discount distribution when line totals are zero
**File:** `AppointmentService.java:845-863` (`distributeAmount`), triggered from `applyComboDiscount` (~751-779) and `distributeDiscount`/`computeEffectiveSubtotal` (~822-837)
**Confidence: CONFIRMED (code-traced)**

The non-last-line branch does `amount.multiply(line.lineRaw()).divide(basisSubtotal, ...)`. `BigDecimal.divide` throws `ArithmeticException` whenever `basisSubtotal == 0`, regardless of dividend. `ClinicService.price`/`Product.price` only enforce `@NotNull`, not a positive-value floor, so a ₹0 item is persistable. Two ₹0 items in one combo (or a combo whose phase-1 discount already zeroes every line, plus a manual whole-appointment discount) crashes `createAppointment`/`updateAppointment` with an unhandled `ArithmeticException`, surfaced to the user as a generic "/ by zero" flash message (see #4 below for what happens to their input when this fires).

### 3. Stock is decremented at booking time, not at COMPLETED — contradicts documented/intended business rule
**File:** `AppointmentService.java` — stock decrement in `createAppointment` (~434-437) and again on every line rebuild in `updateAppointment` (~632-635); `markAsCompleted` (~486-502) has no stock logic at all
**Confidence: CONFIRMED**

CLAUDE.md states stock should decrement "only when an appointment is marked COMPLETED." The actual code decrements stock immediately on create (while status is `SCHEDULED`) and again on every edit. Stock is only restored via `restoreProductStock` on cancel/no-show. Net effect: stock reflects *bookings*, not *actual product usage* — a `SCHEDULED` appointment that never gets completed still shows depleted stock until/unless cancelled, and completing an appointment doesn't further deduct anything. This is a functional deviation from the documented rule that will corrupt inventory reconciliation.

### 4. Validation failures during appointment save/update discard the entire submitted form
**File:** `AppointmentController.java` — `save` (~166-177), `update` (~277-287)
**Confidence: CONFIRMED — reproduced live**

Any exception from `createAppointment`/`updateAppointment` (insufficient stock, "at least one service required," amount-paid-exceeds-total, insufficient wallet balance, or the `ArithmeticException` from #2) is caught generically and redirects to `/appointments/new` or `/appointments/{id}/edit` with only a flash error — none of patient/therapist/date/services/products/discount/wallet input is preserved.

**Reproduced:** Submitted a new appointment with `newPaymentAmount=99999` against a ₹1500 total. Server correctly rejected it ("Amount paid (₹99999) cannot exceed the grand total (₹1500.00)") but redirected to a **blank** `/appointments/new` form — patient, therapist, date, and service selections all lost. Contrast with the double-booking conflict path, which explicitly re-renders the form with everything intact. Staff hitting any of the above validation errors must re-enter the whole appointment from scratch.

### 5. No global exception handling exists anywhere — every uncaught error is a raw 500 with a full stack trace
**Files:** entire codebase — `grep -r "@ControllerAdvice\|@ExceptionHandler"` returns zero matches; `src/main/java/com/clinic/healinghouse/exception/` (documented in CLAUDE.md's architecture section) does not exist; no custom `templates/error.html`; no `server.error.*` config
**Confidence: CONFIRMED — reproduced live, root cause of findings #6-9**

This is the single biggest gap in the app and the root cause of most 500s below. Every controller that doesn't hand-roll its own try/catch leaks Spring's default error response straight to the browser/API caller, including a full Java stack trace (verified live — see reproductions below). Some detail-page controllers (`PatientController.detail`, `TherapistController.detail`, `AppointmentController` edit-lookup) do wrap lookups in local try/catch + redirect, but this is done ad-hoc per-controller, not consistently, so coverage is patchy (see #6).

### 6. Edit/delete endpoints for master data throw raw 500s on a missing/stale ID
**Files:** `PatientController.java:117` (`editForm`), `TherapistController.java:126`, `TreatmentController.java:54`, `ProductController.java:55`, `ComboController.java` editForm (~53) and `detail` AJAX endpoint (~107); corresponding delete/deactivate POST handlers
**Confidence: CONFIRMED — reproduced live**

None of these wrap their `xService.getById(id)` call in a try/catch, unlike the equivalent `detail()` methods for Patient/Therapist which do. Reproduced for every module:
```
GET /patients/999999/edit    -> 500
GET /therapists/999999/edit  -> 500
GET /products/999999/edit    -> 500
GET /services/999999/edit    -> 500
GET /combos/999999/edit      -> 500
GET /combos/999999/detail    -> 500, full stack trace in JSON body (this is the AJAX
                                 endpoint the appointment-form combo picker calls —
                                 a combo deactivated mid-session breaks the picker)
```
`/appointments/999999/edit` is the one exception — it redirects gracefully (302). This inconsistency will surprise staff who click a stale "Edit"/"Delete" link (e.g., a second browser tab where another user already removed the record).

### 7. Pagination: negative page number crashes every list page
**File:** `PaginationUtil.java:10-12` (`clampPageSize` clamps `size` to [1,100] but never touches `page`); every list controller passes the raw `page` param straight into `PageRequest.of(page, pageSize)`
**Confidence: CONFIRMED — reproduced live**

`GET /patients?page=-1` → 500 (`IllegalArgumentException: Page index must not be less than zero`), reproduced live. Same applies to every other list page (`/therapists`, `/products`, `/services`, `/combos`, `/tags`) since they all share the same unclamped pattern.

### 8. Duplicate patient phone number throws a raw 500 instead of a friendly validation message
**File:** `Patient.java:33` (`@Column(unique = true)` on `phone`); `PatientController.save()` (~124) has no catch for `DataIntegrityViolationException`
**Confidence: CONFIRMED — reproduced live**

Submitting a new patient with a phone number already in use crashes with an uncaught `DataIntegrityViolationException` / `ConstraintViolationException`, full stack trace returned to the browser, instead of a "phone number already registered" message next to the field.

### 9. Renaming/merging the "Commission" or "Bonus" tag silently zeroes commission clinic-wide
**Files:** `CommissionCalculator.java:30,32` (hardcoded magic strings `"Commission"`/`"Bonus"`, matched case-insensitively); `TagService.rename()` (~77) and `TagService.merge()` (~91) have no awareness of these two names
**Confidence: CONFIRMED (code-traced)**

An admin who fixes a typo (renames "Commission" → "Commisssion") or merges it into another tag gets no warning, confirmation, or audit trail — every previously-tagged service/product line silently stops contributing to `servicesRevenue`/`productsRevenue`, and every therapist's commission/bonus payout is affected starting immediately, discoverable only by noticing the next payout report looks wrong. Given how load-bearing these two exact tag names are to the entire commission business rule, the Tags admin UI should either protect them or warn loudly before a rename/merge that touches them.

---

## Medium

### 10. Patient Acquisition report's retention rate is inflated 100x in CSV/PDF exports (but correct on the HTML page)
**Files:** `ReportService.java:187-190` (`retentionRate()` already returns a 0-100 value, e.g. `50.0` for 50%); `CsvExportUtil.java:288-291` and `PdfExportUtil.java:722-725` (`formatPercentage(Double)` does `value * 100`, assuming a 0-1 fraction)
**Confidence: CONFIRMED (code-traced, cross-checked against template)**

`reports/patients.html` correctly renders the raw value with a `%` suffix and no extra scaling. The CSV/PDF export path re-multiplies by 100. A period with 50% retention shows "50.0%" on screen but **"5000.00%"** in the exported CSV/PDF. Affects both "Overall Retention Rate" and every row of the per-therapist retention column, unconditionally, on every export of this report.

### 11. Dashboard "today's appointments" KPI count can be off-by-one at the midnight boundary
**Files:** `AppointmentRepository.java:88` (`countByAppointmentDateTimeBetween` — Spring Data derived query compiles to inclusive `BETWEEN`); `DashboardService.java:43-44` calls it with `(today.atStartOfDay(), tomorrow.atStartOfDay())` — both ends inclusive
**Confidence: CONFIRMED (code-traced)**

Contrast with `getTodayAppointments()` → `findTodayAppointments` (`AppointmentRepository.java:53-60`), which explicitly uses `< endOfDay` (exclusive). An appointment booked at exactly `00:00:00` the next day is counted in **both** today's and tomorrow's KPI count, and the KPI number can differ by 1 from the appointment list shown just below it on the same dashboard.

### 12. Commission rounding can diverge ₹0.01 from the documented single-formula calculation
**File:** `CommissionCalculator.java:113-116`
**Confidence: PLAUSIBLE (low materiality)**

Code rounds `serviceCommission` and `productCommission` independently (`.setScale(2, HALF_UP)` each) then adds them, rather than summing revenues first and rounding once, as CLAUDE.md's documented formula implies. Example: `servicesRevenue=333.33`, `productsRevenue=333.34`, `rate=0.10` → spec-correct result `66.67`, actual code result `66.66`. Not covered by existing `CommissionCalculatorTests` (fixtures use round numbers that don't hit the rounding boundary).

### 13. Comparison report export allows a "comparison" of just 1 therapist, inconsistent with the on-screen requirement of ≥2
**File:** `ReportController.java` — `comparison()` HTML view (~91) requires `selectedIds.size() >= 2` to render the chart/table (shows "Select at least 2 therapists" otherwise, confirmed live); `exportComparisonReportCsv`/`exportComparisonReportPdf` (~251, 275) only check `selectedIds.isEmpty()`
**Confidence: CONFIRMED — reproduced live**

`GET /reports/comparison/export-csv?therapistIds=1` succeeds (200, valid CSV for one therapist) even though the equivalent HTML page for the same query string tells the user a comparison needs at least 2 therapists selected. Minor but a real inconsistency between the two code paths.

### 14. `PdfExportUtil` generation methods have no try/finally — a mid-generation exception leaks ThreadLocal state and leaves resources unclosed
**File:** `PdfExportUtil.java` — every `generate*Pdf` method (~118-244) calls `newDocument()` (sets `CURRENT_REGULAR_FONT`/`CURRENT_BOLD_FONT`/`CURRENT_FOOTER_HANDLER` ThreadLocals) then several `document.add(...)` calls, then `finish()` (which does the `.remove()` cleanup) — with no `try/finally` wrapping
**Confidence: CONFIRMED (structural), impact PLAUSIBLE**

If any `document.add(...)` throws mid-layout, `finish()` never runs: the ThreadLocals are never cleared and the underlying `PdfDocument`/output stream is never closed. This is exactly the risk the project's own design notes call out about needing to pair `initFontsForDocument()` with `.remove()`. Worth hardening with try/finally even though no live crash was found in this pass (all 5 report exports generated successfully with current seed data).

### 15. Deactivating a service/product doesn't cascade to combos that contain it
**Files:** `TreatmentService.deactivate`/`ProductService.deactivate` only flip the item's own `active` flag; `ComboService` (`getById`, `computeOriginalPrice`/`computeComboPrice`, `toSuggestion`) and `ComboController.detail` never check item `active` status
**Confidence: PLAUSIBLE**

Staff can deactivate a service/product intending to stop selling it, but if it's bundled in an active combo, the combo picker keeps offering it (at its live price) indefinitely — undermining the intent of the "active flag, no hard delete" pattern.

### 16. Wallet's lazy "create on first use" has an unguarded check-then-act race
**File:** `WalletService.java:48-58` (`getOrCreateWallet`), `:131-138` (`persistBalance`)
**Confidence: PLAUSIBLE**

`findById` → `orElseGet(save)` with no pessimistic lock. Two concurrent first-time wallet operations for the same patient (e.g. two staff topping up simultaneously) can both miss the `findById` and both try to insert a `PatientWallet` row sharing the same `@MapsId` PK; `persistBalance`'s catch only handles `ObjectOptimisticLockingFailureException`, not the resulting `DataIntegrityViolationException` — which would surface as an uncaught 500 (compounding finding #5).

---

## Low

### 17. No UI path exists to reactivate a deactivated patient/therapist/service/product/combo
**Files:** every `*Controller` — only `deactivate`/`delete` endpoints exist, no `activate`/`reactivate` route anywhere; corresponding `form.html` templates round-trip a hidden `active` field with no editable toggle
**Confidence: CONFIRMED**

An accidental "Deactivate" click on any master-data record is effectively permanent short of a direct database edit.

### 18. Two wallet pencil-edit toggles are only one-directionally mutually exclusive — can silently discard a manual wallet correction
**File:** `appointments/form.html` — `togglePrepaidEdit()` (~951-979) disables the wallet controls when entering prepaid-edit mode, but `toggleWalletAppliedEdit()` (~981-1005) never disables the prepaid controls the other way around
**Confidence: CONFIRMED (code-traced against `AppointmentService.updateAppointment:650-661`)**

Click order: open wallet-correction mode → type a new wallet target → then click the prepaid pencil. `togglePrepaidEdit()` disables `walletAppliedInput` while it still holds the just-typed value; a disabled input is omitted from the POST, and `updateAppointment` falls back to the previous wallet amount when the param is absent — so the user's correction is silently reverted with no warning, the same failure shape the two-pencil design was meant to prevent, just via a different click order. Also leaves the wallet box stuck (its "edit" button is disabled too) until the prepaid pencil is toggled back off.

### 19. Tag rename/`findOrCreate` and combo-form client validation: minor gaps
**Confidence: PLAUSIBLE, low severity**
- `TagController.rename()` has no blank-name guard before calling the service; only saved by `Tag.name`'s `@NotBlank` throwing a `ConstraintViolationException` that the controller's generic catch turns into an unfriendly message rather than a clear "name cannot be blank."
- `TagService.findOrCreate` (~67-75) is a check-then-act race on brand-new tag names — two concurrent submissions of the same new tag name can both pass the existence check and one loses to the DB's unique constraint (uncaught, see #5).
- `combos/form.html`'s submit handler doesn't block a 0-item combo client-side the way `appointments/form.html` blocks 0 service lines (server-side `ComboService` does reject it, so no data-integrity impact — just a worse error UX).
- `patients/detail.html`'s therapist calendar link and `therapists/calendar.html`'s mobile breakpoint (`window.matchMedia` check) is only evaluated once at page load — rotating/resizing after load doesn't switch between the desktop grid and mobile agenda view.
- `appointments/form.html`'s "Top Up" link before a patient is selected uses CSS-only `.disabled` (pointer-events block), not a true disabled state — a keyboard user tabbing to it and pressing Enter can still open the modal, which would then submit against `patientId=0`.

### 20. Non-deterministic tie-breaking for "top therapist/service/product" on the patient history page
**File:** `PatientHistoryService.java:116-122` (`topEntry`/`topEntryByName`)
**Confidence: CONFIRMED, cosmetic**

Ties are broken by `HashMap` iteration order rather than any defined rule (alphabetical, most-recent, etc.) — the displayed "favorite" can flip between page loads for a patient with a genuine tie.

---

## Verified working correctly (no bug — noted so this isn't re-investigated later)

- Two-phase combo + manual discount math: live-created an appointment (Patient 1, "Massage Combo" — 3 services + 1 product, raw ₹5599) with a 10% manual discount on top. Combo savings (₹599), discount (₹500, correctly computed against the post-combo ₹5000 effective subtotal, not the raw ₹5599), and final grand total (₹4500) all matched by hand-calculation; per-line effective totals summed exactly to the grand total.
- Wallet auto-reversal on cancel: applied ₹300 wallet to an appointment (balance ₹1000→₹700), cancelled the appointment, balance correctly reverted to ₹1000.
- Double-booking conflict warning: booking a second appointment for a therapist already scheduled in an overlapping window correctly re-rendered the form with a warning banner and a "Save anyway" (`forceSave`) checkbox rather than silently blocking or silently allowing it.
- Server-side amount-paid-exceeds-grandTotal validation correctly rejects the overpayment (see #4 for the separate bug about losing form state on rejection).
- Tag merge/rename/delete correctly repoints `ClinicService`/`Product` associations with no orphans (aside from the Commission/Bonus blind spot in #9).
- No SQL injection risk anywhere — all queries are parameterized JPQL/derived methods.
- No XSS: no `th:utext` anywhere in templates; dynamic `innerHTML` call sites all escape user-controlled text first.
- `DataSeeder` checks emptiness per-table (not globally) inside one `@Transactional` — a crash mid-seed rolls back cleanly.
- Multi-page PDF export (Period report, 2 pages) generated successfully with correct pagination — the previously-fixed `FooterEventHandler` approach holds up.

---

## Test data created during this review (dev DB)

While probing live behavior, the following were created against `healing_house_clinic` and were **not** cleaned up (the app has no delete function for appointments, only status transitions/cancellation, so full removal isn't possible via the UI):
- Appointments #7 (cancelled), #8, #9, #10, and #11-ish range (one deliberately-rejected overpay attempt that did **not** persist, per finding #4's redirect-on-error behavior).
- Patient 1's wallet: topped up ₹1000, ₹300 applied then reversed on cancel (net: ₹1000 balance, one extra `WalletTransaction` pair in the ledger).
- Patient 2's wallet: topped up ₹1000, ₹400 currently still applied to Appointment #10 (see finding #1 — this is now stuck against the wrong patient).
- One rejected "Duplicate Test" patient save (phone `9811100001`) — did not persist (finding #8 confirmed the crash, not the write).

Recommend a staff member review/clean up appointments #7-10 and patient 1/2's wallet ledgers before this dev database is used for anything beyond bug verification.
