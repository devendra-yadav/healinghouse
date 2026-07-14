# Bug Report — Full Application Review (v2)

**Date:** 2026-07-14
**Method:** Static code review (5 parallel focused passes: discount/combo pricing, commission/bonus/reporting, appointment core totals & double-booking conflicts, wallet/prepaid-balance calculations, and a dedicated pass verifying every fix claimed by commit `353336a` "bug fixes done as per the bug report v1") + the full test suite run (`mvnw test`). **Update:** all 13 new findings below (#1–#12; #13 was retracted as a false positive) have since been fixed in this same session — see the "Fix applied" note under each. Final state: 34/34 tests passing (31 original + 3 new regression tests), clean compile. The concurrency finding (#1) was fixed via code inspection/reasoning, not a live concurrent-request reproduction — worth an eventual live double-submit test in a staging environment, though the fix (`@Version` + `saveAndFlush`) is a standard, well-established pattern already used elsewhere in this codebase (`PatientWallet`).
**Scope:** Builds on `requirements/Bug_Report_v1.md`. That report's 20 findings are re-verified against current code first; only *new* findings are detailed in full below.

---

## Part A — Status of Bug_Report_v1's 20 findings

**All 20 are FIXED** by commit `353336a`. Verified by reading the current state of every referenced file/line (not just trusting the commit message):

| # | Topic | Verdict |
|---|---|---|
| 1 | Patient reassignment leaked wallet funds | **FIXED** — `AppointmentService.updateAppointment` (~line 552) now throws if patient is changed while `walletAmountApplied > 0` |
| 2 | Division-by-zero in discount distribution | **FIXED** — `distributeAmount` (~852) now guards `basisSubtotal.signum() <= 0` before dividing |
| 3 | Stock decremented at booking, not COMPLETED | **FIXED** — `createAppointment` only validates availability now; `markAsCompleted` decrements stock |
| 4 | Validation failures discarded the whole form | **FIXED** — new `renderAppointmentFormWithError()` re-populates the full form on exception |
| 5 | No global exception handling | **FIXED** — `exception/GlobalExceptionHandler.java` (`@ControllerAdvice`) + `templates/error.html` added |
| 6 | Master-data edit/delete 500s on stale ID | **FIXED** — via the global handler (`EntityNotFoundException` → graceful redirect); combo AJAX detail returns JSON 404 |
| 7 | Negative `page` param crashes list pages | **FIXED** — `PaginationUtil.clampPage()` added, used everywhere |
| 8 | Duplicate phone → raw 500 | **FIXED** — `PatientController.save()` catches `DataIntegrityViolationException`, shows field error |
| 9 | Renaming "Commission"/"Bonus" tag silently breaks payouts | **FIXED** — `TagService` now rejects rename/merge of those two tags |
| 10 | Retention rate 100x inflated in CSV/PDF | **FIXED** — export formatters no longer re-multiply by 100 |
| 11 | Today's-appointments KPI off-by-one at midnight | **FIXED** — repository query now uses an exclusive upper bound |
| 12 | Commission rounding could diverge from documented formula | **FIXED for payout math** — `totalCommission`/`totalVariablePay` now sum-then-round; see new finding #11 below for the still-cosmetic display split |
| 13 | Comparison export allowed 1-therapist export | **FIXED** — export endpoints now enforce the same `size() >= 2` as the HTML page |
| 14 | PDF export leaked ThreadLocal state on exception | **FIXED** — every `generate*Pdf` now wraps `document.add(...)` in try/finally |
| 15 | Deactivating a service/product didn't cascade to combos | **FIXED** — `ComboService.isSelectable()` filters combos containing an inactive item |
| 16 | Wallet lazy-create race | **FIXED for the described race**, but see new finding #7 below — the fix's exception-catch may not reliably trigger |
| 17 | No reactivate path for master data | **FIXED** — activate/reactivate endpoints + "Show Inactive" toggle added across all modules |
| 18 | Wallet/prepaid pencil-edits only one-way exclusive | **FIXED** — now bidirectionally disables each other |
| 19 | Assorted minor gaps (tag blank-name, combo 0-item, calendar resize, top-up a11y) | **FIXED** — all sub-items addressed |
| 20 | Non-deterministic "top therapist/service" tie-break | **FIXED** — now a deterministic name-based comparator (see new finding #13: the comment describing the order is now wrong) |

---

## Part B — New findings (this pass)

Ordered by severity.

### 1. [CRITICAL] ~~No optimistic locking on `Appointment`~~ — **FIXED**
**Files:** `entity/Appointment.java` (no `@Version` field — confirmed by direct search, zero matches), `service/AppointmentService.java` — `cancelAppointment`/`markAsNoShow` (509–535), `updateAppointment` wallet block (655–680).
**Confidence: PLAUSIBLE (code-traced, not reproduced live — recommend a live double-submit test before treating as fully confirmed)**

**Fix applied:** Added `@Version private Long version;` to `Appointment` (mirroring `PatientWallet`'s existing pattern). Added `AppointmentService.saveWithConflictCheck()`, which calls `appointmentRepository.saveAndFlush(...)` instead of the deferred `.save(...)` and translates a version conflict into a friendly `IllegalStateException` ("This appointment was just updated by someone else. Please refresh and try again."), exactly mirroring `WalletService.persistBalance`'s existing pattern for `PatientWallet`. Wired into the three race-prone write paths: `cancelAppointment`, `markAsNoShow`, and `updateAppointment`'s final save. Because the flush is forced immediately and the whole method runs inside one `@Transactional` boundary, a losing concurrent transaction now fails *before* commit — rolling back any wallet reversal/debit it already flushed earlier in the same method, so no double-credit/double-debit can survive. Controllers already catch generic `Exception` around all three calls (`AppointmentController.cancel`/`noShow`/`update`) and surface `e.getMessage()` via a flash message, so no controller changes were needed. Updated `AppointmentServiceTests`' Mockito stub to also answer `saveAndFlush(...)` (it previously only stubbed `.save(...)`). Full suite: 31/31 passing.

`PatientWallet` has `@Version` (protects concurrent writes to the *same* balance), but `Appointment` has none. Every wallet mutation is gated by re-reading `appt.getStatus()` / `existing.getWalletAmountApplied()` from an unversioned entity.

**Scenario A — double cancel:** Appointment #42, `grandTotal=1000`, `walletAmountApplied=1000`, `status=SCHEDULED`. Two near-simultaneous "Cancel" submissions (double-click, or a client retry after a slow response) both read the appointment while still `SCHEDULED`, both pass the status guard, both call `reverseFullWalletIfAny`. Each call independently re-reads the wallet's current balance (protected individually by `@Version`) and adds 1000 to it — nothing stops the *second* logically-redundant reversal from succeeding after the first one commits cleanly. Net result: the patient's wallet gains ₹2000 for a single ₹1000 cancellation.
**Scenario B — double-submitted edit:** two concurrent submits of the same "apply ₹500 more from wallet" edit each independently compute `walletDelta=500` from their own stale read of `previousWalletApplied=0`, and each debit the wallet ₹500 — the wallet loses ₹1000 total while the appointment record only shows ₹500 applied (last writer wins), permanently losing track of the extra debit.

**Expected:** a second, logically-redundant reversal/debit against the same appointment should be rejected. **Fix direction:** add `@Version` to `Appointment` (turns the second transaction into an `ObjectOptimisticLockingFailureException`, same pattern already used for `PatientWallet`), or take a pessimistic read lock in `cancelAppointment`/`markAsNoShow`/`updateAppointment`.

### 2. [HIGH] ~~`updateAppointment` silently mutates patient/therapist/date/time/duration on non-SCHEDULED appointments~~ — **FIXED**
**Files:** `service/AppointmentService.java` lines 539–581 (docstring at 539–540 vs. unconditional sets at 564–581); `controller/AppointmentController.java` — `editForm` (196–240) and `update` (243–274) have no status check at all.
**Confidence: CONFIRMED (code-traced)**

**Fix applied:** `updateAppointment` now computes `boolean editable = existing.getStatus() == AppointmentStatus.SCHEDULED` once, up front. Patient/therapist/appointmentDateTime/durationMinutes, the line-item/discount/combo rebuild, and the entire wallet target/delta block (including the patient-reassignment-vs-wallet guard) are all now gated behind `editable`. `notes`, `paymentMethod`, and cumulative `amountPaid` (prepaid correction + new payment) remain unconditionally editable, matching the method's own documented contract ("only notes and payment info"). This also closes the wallet-specific half of this finding: since the wallet block no longer runs at all once an appointment is non-SCHEDULED, staff can no longer re-apply or top up wallet funds against a cancelled/no-show/completed appointment via the edit form — `walletDelta` stays `0` and neither `applyToAppointment` nor `reverseForAppointment` fires. No controller/template change was needed for the backend invariant to hold (the edit page remaining reachable by URL for non-SCHEDULED appointments is now harmless — submissions simply can't move any of the frozen fields). Full suite: 31/31 passing.

The javadoc states *"For non-SCHEDULED appointments only notes and payment info are updated"* — but `patient`, `therapist`, `appointmentDateTime`, and `durationMinutes` are all set unconditionally before the `if (status == SCHEDULED)` gate that only protects the line-item/discount rebuild. The UI hides the "Edit" link for non-SCHEDULED appointments, but nothing stops a stale tab, a bookmarked edit URL, or a direct POST from reaching `/appointments/{id}/edit` → `/update` on a `COMPLETED`/`CANCELLED`/`NO_SHOW` appointment and rewriting when it happened and who ran it — corrupting the therapist calendar, date-range reports, and commission attribution for an appointment whose stock/pricing/commission data is otherwise frozen.

**Same root cause also lets wallet funds be re-applied to a dead appointment:** the wallet target/delta block (651–680) is likewise ungated by status. `cancelAppointment`/`markAsNoShow` deliberately zero out `walletAmountApplied` via `reverseFullWalletIfAny` — but since `update()` doesn't check status, staff editing a *already-cancelled* appointment's "payment info" can submit a nonzero `walletAmountApplied` and have `walletService.applyToAppointment` debit the patient's wallet again for an appointment where no service was ever rendered.

**Expected:** `updateAppointment` should reject (or silently ignore) changes to date/time/therapist/patient/wallet once `status != SCHEDULED`.

### 3. [HIGH] ~~Per-line therapist reassignment bypasses double-booking conflict detection entirely~~ — **FIXED**
**Files:** `controller/AppointmentController.java` 317–346 (`reassign-therapist` endpoints); `service/AppointmentService.java` `reassignServiceLineTherapist`/`reassignProductLineTherapist` (691–719).
**Confidence: CONFIRMED (code-traced)**

`findConflicts` is only ever called from the create/update save flow. The dedicated per-line reassignment endpoints — whose entire purpose is changing a line's therapist, i.e. exactly the kind of change the conflict rule says must be checked ("main + all line overrides") — call neither `findConflicts` nor any conflict check. Reassigning a line's therapist to someone already double-booked at that time saves silently with no warning banner and no "Save anyway" gate, producing an unflagged double-booking that never surfaces on the calendar or in reports.

**Fix applied:** Extracted `findConflicts`'s per-therapist inner loop into a new reusable method, `findConflictsForTherapist(therapistId, start, end, excludeAppointmentId)`; `findConflicts` now calls it once per therapist on the form. `reassignServiceLineTherapist`/`reassignProductLineTherapist` now call it too (checking just the one new therapist against the appointment's own `[appointmentDateTime, getEndDateTime())` window, excluding the appointment itself) and return the conflict list instead of a bare entity — an empty list means the reassignment was applied; a non-empty list means nothing was persisted (same "warn, never hard-block" contract as create/update). Both signatures gained a `boolean forceReassign` parameter mirroring `forceSave`. `AppointmentController`'s two reassignment endpoints now flash the conflicts (plus which line/therapist triggered them) back to the referring page instead of blindly reporting success; `appointments/detail.html` gained a warning banner (mirroring `appointments/form.html`'s existing conflict banner) that lists the conflict and offers a one-click "Reassign Anyway" button, which resubmits the same request with `forceReassign=true`. Full suite: 31/31 passing.

### 4. [MEDIUM-HIGH] ~~`Therapist.isOwner()` infers the commission-exempt "owner" case from two zero/null fields instead of an explicit flag~~ — **FIXED**
**Files:** `entity/Therapist.java` 66–69; consumed by `service/CommissionCalculator.calculateEarnings`.
**Confidence: CONFIRMED (code-traced)**

**Fix applied:** Replaced the hand-written, inference-based `isOwner()` with a real persisted `boolean owner` column (`@Column(nullable = false, columnDefinition = "TINYINT(1) NOT NULL DEFAULT 0")`, `@Builder.Default owner = false`). Naming it `owner` means Lombok's generated accessor is still `isOwner()`, so every existing call site (`CommissionCalculator`, `therapists/list.html`'s `${t.owner}`, `therapists/detail.html`'s `${therapist.owner}`) needed zero changes. New therapists now default to `owner = false` regardless of whether `commissionRate`/`fixedMonthlySalary` have been configured yet — the exact bug is closed.

Two follow-on pieces to keep this correct in practice:
- **Existing-data backfill:** added `config/OwnerFlagBackfill.java`, a small always-on `CommandLineRunner` (deliberately *not* under `DataSeeder`'s dev/test-only `@Profile`, since prod/preprod have the same pre-existing owner row needing the same one-time fix) that finds the therapist named "Marcia Gomes Yadav" and flags her `owner = true` if not already set — idempotent, a no-op on every subsequent startup. Verified live against the actual dev database: before the fix all 5 therapists showed `owner=false` after the column was added; after this runner executed, `Marcia Gomes Yadav(owner=true)` while the other four remained `false` (confirmed via a temporary diagnostic log, since removed).
- **UI:** `therapists/form.html`'s "Payout Configuration (leave blank for owner)" convention — the very mechanism that made this bug possible — is replaced with an explicit "This therapist is the owner" checkbox (`th:field="*{owner}"`), so designating a new owner (however rare) no longer depends on staff remembering to leave three unrelated fields blank.
- **Regression test:** added `CommissionCalculatorTests.therapistWithUnconfiguredPayoutFieldsIsNotSilentlyTreatedAsOwner` — a therapist with `commissionRate`/`fixedMonthlySalary` both `null` and `owner` left at its default `false` now correctly goes through the full non-owner earnings computation instead of the owner short-circuit. Also updated the existing owner-fixture test to set `.owner(true)` explicitly (it previously relied on the now-removed zero-value inference). Full suite: 32/32 passing (31 + 1 new).

```java
public boolean isOwner() {
    return (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) == 0)
            && (fixedMonthlySalary == null || fixedMonthlySalary.compareTo(BigDecimal.ZERO) == 0);
}
```
`therapists/form.html` labels these fields "Payout Configuration (leave blank for owner)" — so leaving them blank is a deliberate way to mark the owner, but it's also indistinguishable from a brand-new regular therapist who hasn't had payout terms configured yet (both fields default to `null`/unset at creation). **Scenario:** a new therapist is added and saved before her `commissionRate`/`fixedMonthlySalary` are set (a plausible real onboarding order — profile first, payout terms once negotiated). `isOwner()` returns `true`, so `CommissionCalculator` zeroes her commission/bonus/`totalVariablePay` — while `allServicesRevenue`/`allServicesCount` (the "All" reporting columns) still look completely normal, making the ₹0 payout easy to miss until the next payroll run. **Fix direction:** an explicit `isOwner` boolean column rather than value-inference.

### 5. [MEDIUM] ~~`distributeAmount`'s "last line absorbs the rounding remainder" can go negative~~ — **FIXED**
**File:** `service/AppointmentService.java` `distributeAmount` (850–874).
**Confidence: PLAUSIBLE (reasoned from the rounding-error bound, not reproduced)**

Each non-last line's share is independently rounded `HALF_UP` (867–869) and can be over-allocated by up to ₹0.005 before its own `setScale(2, HALF_UP)`. With enough lines (~50+, roughly equal value) and a discount amount small relative to the line count, the accumulated over-allocation can exceed the last line's fair share, driving `share = amount − allocated` negative — so `discountedLineTotal = lineRaw − share` becomes *larger* than `lineRaw` for that one line. The appointment-level total still nets out correctly (by construction), but that one line's displayed/effective price goes up instead of down under a "discount."

**Fix applied:** `distributeAmount` now clamps every line's share (including the remainder-absorbing last line) to `[0, lineRaw]`, then walks any leftover from clamping backward through the earlier lines — nudging their shares up or down within their own bounds — until it's fully absorbed, so the exact-sum-to-`amount` invariant still holds alongside the new non-negative/non-excessive guarantee. Added `AppointmentServiceTests.createAppointment_manyEqualLinesTinyDiscount_noLineShareGoesNegativeOrExceedsRaw` — 50 equal ₹1 lines with a ₹0.25 flat discount (each non-last line's true share is exactly 0.005, which rounds up to 0.01, reproducing the old bug) — asserting every line's discount share is within `[0, lineRaw]` and the shares still sum exactly to the discount amount.

### 6. [MEDIUM] ~~Combo picker's live preview can silently diverge from what actually gets saved~~ — **FIXED**
**Files:** `templates/appointments/form.html` — `SERVICE_DATA`/`PRODUCT_DATA` snapshotted once at page load (422–423) drive the running-total preview; contrast with `ComboController.search()`/`ComboService.toSuggestion()` which compute prices **live** from the DB at AJAX-search time.
**Confidence: PLAUSIBLE (code-traced)**

If a service/product's price is edited by another staff member while this form is open, the combo picker's search-result "Save ₹X" badge (live price) won't match the per-line rate shown after clicking "Add" (stale page-load price), and neither necessarily matches the final `grandTotal` the server computes at submit.

**Fix applied:** `ComboDetailDTO.ComboDetailItemDTO` gained a `price` field, populated from the live `Combo` entity's current catalog prices in `ComboController.detail()` (the same AJAX call the picker already makes when a combo is added). `addComboGroup()` now threads that live price through to `addServiceRow`/`addProductRow`, which stash it as `row.dataset.livePrice` for combo-locked rows; `updateTotals()` now prefers `row.dataset.livePrice` over the stale `serviceMap`/`productMap` lookup when present. The per-line rate shown at add-time and the running total now always match the price the server will actually charge, closing the divergence window (a catalog price change after the combo's own price was fetched is still possible in principle, but that window is now just the instant between the AJAX response and clicking "Add," not the entire time the form stays open).

### 7. [MEDIUM] ~~The v1 fix for the wallet-creation race (#16) may not reliably trigger~~ — **FIXED**
**File:** `service/WalletService.java` `getOrCreateWallet` (49–66), `persistBalance` (139–146).
**Confidence: PLAUSIBLE (code-traced)**

The `DataIntegrityViolationException` catch wrapped `walletRepository.save(...)`, not `saveAndFlush(...)`. Under Hibernate's default deferred-flush behavior, this INSERT typically doesn't execute synchronously at this call site, so a genuine PK collision between two concurrent first-time wallet operations for the same patient was more likely to surface later — at the next flush/commit point, outside this try/catch.

**Fix applied:** Switched `getOrCreateWallet`'s create path to `saveAndFlush(...)`, forcing the INSERT to run synchronously so a concurrent-creation race now genuinely surfaces in this method's own catch block rather than escaping it. Updated `WalletServiceTests.topUp_createsWalletAndCreditsBalance_whenWalletDoesNotExist`, which now correctly expects two `saveAndFlush` calls (the zero-balance creation, then the credited-balance update) instead of one. `persistBalance` was left as-is — it only runs on an UPDATE against an already-existing row, where a `DataIntegrityViolationException` from a PK collision can't occur.

### 8. [LOW] ~~`getPaymentStatus()` labels a fully-comped appointment "N/A" instead of "PAID"~~ — **FIXED**
**File:** `entity/Appointment.java` 199–206.
**Confidence: CONFIRMED (code-traced)**

```java
if (grandTotal == null || grandTotal.signum() == 0) return "N/A";
```
A 100%-discounted appointment (`grandTotal=0`, `amountPaid=0`, `balanceDue=0` — nothing owed, nothing to pay) was indistinguishable in the UI from an appointment that never got priced at all.

**Fix applied:** Split the check — `grandTotal == null` still returns `"N/A"` (genuinely absent pricing), but `grandTotal.signum() == 0` now returns `"PAID"` directly (nothing owed, nothing to pay). Every consuming template (`appointments/list.html`, `appointments/detail.html`, `patients/detail.html`, `therapists/detail.html`) already has a `"PAID"` badge case, so no template changes were needed.

### 9. [LOW] ~~`@ResponseBody` JSON search endpoints can return HTML/a redirect instead of JSON on an unexpected error~~ — **FIXED**
**Files:** `controller/PatientController.search`, `controller/ComboController.search`, `controller/TagController.search` — none has local exception handling.
**Confidence: CONFIRMED (code-traced)**

The `GlobalExceptionHandler` (added to fix v1 #5) returns Thymeleaf view names (`"error"`) or `"redirect:..."` for uncaught exceptions — correct for normal page controllers, but these three endpoints back `fetch()`-based typeahead/autocomplete JS expecting JSON.

**Fix applied:** `GlobalExceptionHandler`'s three handlers now accept the request's `HandlerMethod` and branch on `expectsJson()` (true for any `@ResponseBody` handler). For a JSON endpoint, the handler writes a small `{"error": "..."}` body directly to the response (via the injected Jackson `ObjectMapper`) with the appropriate status (404/409/500) and returns `null` (Spring's signal that the response was already written, skipping view resolution); non-JSON endpoints keep the original redirect/HTML behavior unchanged. Note: this project is on **Jackson 3.x**, where the package root moved from `com.fasterxml.jackson` to `tools.jackson` — `tools.jackson.databind.ObjectMapper` is the correct import here, not the more commonly-seen `com.fasterxml` one.

### 10. [LOW] ~~Discount-percentage client-side cap silently diverges from the server's hard rejection~~ — **FIXED**
**Files:** `templates/appointments/form.html` `updateTotals()` clamps a >100% typed value to 100 in the live preview; `service/AppointmentService.java` throws `IllegalArgumentException` for the same input.
**Confidence: CONFIRMED (code-traced)**

Not a data-integrity bug (the server-side rejection was already caught and re-rendered the form with an error, per the v1 #4 fix) — but a staff member typing e.g. "150%" saw a preview silently capped at 100% with no indication anything was wrong.

**Fix applied:** Added a small inline warning (`#discountPercentWarning`, styled like the existing `#walletExceedsAlert` pattern) right under the discount value input, toggled by `updateTotals()` whenever `discountType === 'PERCENTAGE' && discountRaw > 100` — so the cap and the reason for it are now visible immediately, before save is even attempted.

### 11. [LOW / cosmetic] ~~Displayed `Service Commission + Product Commission` columns can still differ from `Total Commission` by ±₹0.01~~ — **FIXED**
**File:** `service/CommissionCalculator.java` 113–119.
**Confidence: PLAUSIBLE, low materiality**

v1 #12's fix corrected the actual payout math (`totalCommission`/`totalVariablePay` now sum-then-round). `serviceCommission`/`productCommission` — the two columns shown side-by-side with `Total Commission` in the CSV/PDF earnings export — are still rounded independently and can occasionally not add up to the (correct) total column by a cent, with no footnote explaining why.

**Fix applied:** Added a footnote row to `CsvExportUtil.writeTherapistEarnings` (a trailing CSV row) and a small muted footnote paragraph under `PdfExportUtil.addTherapistEarningsTable`'s table, both explaining that Svc Comm + Prod Comm may differ from Total Comm by up to ₹0.01 due to independent per-category rounding, and that Total Comm is the actual payout figure.

### 12. [LOW] ~~Conflict-detection's DB pre-filter window could theoretically miss an overlap for an unusually long appointment~~ — **FIXED**
**File:** `service/AppointmentService.java` `findConflicts` 201–211.
**Confidence: PLAUSIBLE, theoretical — `durationMinutes` had no upper-bound validation, but real-world durations are ~60min**

The pre-filter widens ±1 day around the *requested* appointment's date, but doesn't account for a *candidate* appointment's own long duration when it starts more than ~24h before the target window.

**Fix applied:** Added `AppointmentService.MAX_DURATION_MINUTES = 24 * 60` and a `validateDuration()` guard, wired into `createAppointment`, `updateAppointment`'s editable block, and `rescheduleAppointment` (the calendar drag/resize path) — all three throw `IllegalArgumentException` past 24 hours. Capping every appointment at 24h makes the existing ±1-day pre-filter window provably safe: no candidate can ever run long enough to overlap a target window while starting outside that margin. The duration field itself is a fixed `<select>` (30/60/90/120 min) in `appointments/form.html`, so no client-side change was needed there. Added `AppointmentServiceTests.createAppointment_durationOver24Hours_throws`.

### 13. [RETRACTED] `PatientHistoryService`'s tie-break comment does *not* actually contradict its own code
**File:** `service/PatientHistoryService.java` `topEntry`/`topEntryByName`.

On closer inspection this finding (from the prior verification pass) was mistaken. The comment says "ties broken alphabetically (ascending)"; the code pairs `Comparator.reverseOrder()`/`.reversed()` with `.max(...)`. Tracing the actual composition: `reverseOrder()` makes the alphabetically-*earlier* of two strings compare as "greater" (e.g. `reverseOrder().compare("Alice","Bob") > 0`), and `.max()` then picks whichever entry is "greater" per that comparator — i.e. the alphabetically-*first* entry among ties. That's exactly what "ascending" tie-break means in the ordinary sense (A before B wins). The comment and the code agree; no change made.

---

## Verified correct in this pass (no bug — noted so it isn't re-investigated)

- Two-phase combo + whole-appointment discount math reconciles exactly: `totalServiceAmount + totalProductAmount − comboDiscounts − discountAmount == grandTotal`, cross-checked against `appointments/detail.html`.
- Overlap check `candidateStart.isBefore(end) && start.isBefore(candidateEnd)` is a correct half-open interval test; back-to-back slots don't conflict; self-exclusion on edit is applied per-therapist correctly.
- Stock decrement fires exactly once, gated by `status == SCHEDULED` in `markAsCompleted`; no reachable state transition re-decrements or needs reversal.
- Commission/bonus queries are correctly per-line-therapist-attributed, correctly tag-filtered (`Commission`/`Bonus`, case-insensitive), and correctly isolated from discounts (`priceAtTime`/`lineTotal`, never `discountedLineTotal`).
- Marcia/owner "All" reporting figures (`allServicesRevenue` etc.) are computed identically regardless of the owner branch — only commission/bonus/variable-pay are zeroed.
- Wallet delta computation, amountPaid double-counting, negative/zero-amount guards, and `getBalanceDue()`/`getPaymentStatus()`'s wallet-agnosticism all check out correct.
- `PdfExportUtil` ThreadLocal font cleanup now runs on both success and exception paths (v1 #14 fix confirmed robust).
- Date-range boundaries are now consistent across `CommissionCalculator`, `ReportAggregator`, `DashboardService`, and all HTML/CSV/PDF report export paths.
- Full test suite (`HealinghouseApplicationTests`, `AppointmentServiceTests`, `CommissionCalculatorTests`, `ReportServiceTests`, `WalletServiceTests`) — 31/31 passing.

---

## Status: all findings resolved

1. ~~**#1** (wallet double-reversal/debit race)~~ — **FIXED**.
2. ~~**#2** (non-SCHEDULED mutation gap, including wallet re-application)~~ — **FIXED**.
3. ~~**#3** (line reassignment skips conflict check)~~ — **FIXED**.
4. ~~**#4** (`isOwner()` inference)~~ — **FIXED**.
5. ~~**#5** (rounding remainder could go negative)~~ — **FIXED**.
6. ~~**#6** (combo picker stale price preview)~~ — **FIXED**.
7. ~~**#7** (wallet-creation race fix didn't reliably trigger)~~ — **FIXED**.
8. ~~**#8** (fully-comped appointment mislabeled "N/A")~~ — **FIXED**.
9. ~~**#9** (JSON search endpoints could leak HTML/redirect on error)~~ — **FIXED**.
10. ~~**#10** (discount % cap silently diverged from server)~~ — **FIXED**.
11. ~~**#11** (commission column rounding footnote)~~ — **FIXED**.
12. ~~**#12** (conflict pre-filter window / unbounded duration)~~ — **FIXED**.
13. **#13** — **RETRACTED**, not a bug (see entry above).

Test suite: 34/34 passing (31 original + 3 new regression tests). Every fix in this document was compiled and verified against the full suite before being marked FIXED.
