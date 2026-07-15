# Bug Report — Full Application Review (v3)

**Date:** 2026-07-15
**Method:** Static code review — five parallel focused passes covering the entire application: (1) appointment core money/state logic (discount engine, combo two-phase discounting, optimistic locking, double-booking conflicts, stock), (2) patient wallet / prepaid balance, (3) commission/bonus calculation and all six reports + CSV/PDF export, (4) master-data lifecycle — soft delete, the new permanent-delete feature, and general CRUD, (5) frontend money JS and general web security (XSS, CSRF, IDOR, injection). Each pass re-verified every finding claimed fixed in `Bug_Report_v1.md` (20 findings) and `Bug_Report_v2.md` (13 findings) against current code rather than trusting the prior write-ups, then hunted for new bugs — particularly at the seams between features that were each fixed correctly in isolation but may not compose. Full test suite run as a baseline: **34/34 passing, clean build.**
**Scope:** Builds on `Bug_Report_v1.md` and `Bug_Report_v2.md`. All 33 previously-fixed findings remain fixed in current code, with two exceptions surfaced this pass where an earlier fix was correct for the scenario it targeted but incomplete against a related code path — see #2 and #6 below. Also newly reviewed: the permanent-delete feature (commit `b71d17d`) and UI tweaks (commit `9a76871`), neither covered by any prior report.
**This report documents findings only — no fixes have been applied.**

---

## Summary

| # | Severity | Finding | Area |
|---|----------|---------|------|
| 1 | CRITICAL | `TagService.delete()` has no guard against deleting the Commission/Bonus tags | Tags / Commission |
| 2 | HIGH | Stale page-load baseline on the edit form causes silent lost-update of payments and wrong wallet reversal on concurrent edits | Appointment / Wallet |
| 3 | HIGH | Per-line therapist reassignment bypasses `Appointment`'s optimistic lock — can be silently overwritten | Appointment concurrency |
| 4 | HIGH | Stock is never re-validated at `markAsCompleted`, the only point it's actually consumed — inventory can be oversold | Appointment / Inventory |
| 5 | HIGH | Actual Revenue report folds non-COMPLETED "advance payment" amounts into COMPLETED-only summary cards | Reporting |
| 6 | MEDIUM-HIGH | A genuine optimistic-lock conflict still discards the whole submitted form | Appointment UX/data-entry |
| 7 | MEDIUM | `markAsCompleted` doesn't use the shared conflict-safe save path other status transitions use | Appointment concurrency |
| 8 | MEDIUM | No CSRF protection on any money-mutating endpoint | Security |
| 9 | MEDIUM | Per-therapist revenue/comparison reporting groups by therapist name string, not ID | Reporting |
| 10 | LOW-MEDIUM | Unvalidated open-redirect via `returnUrl` on cancel/complete/no-show/wallet endpoints | Security |
| 11 | LOW | Combo lookup queries lack `DISTINCT`, inflating "removed from N combos" flash-message counts | Combos |
| 12 | LOW | `ComboService.save()` doesn't re-check selected items are still active (race window) | Combos |
| 13 | LOW | A combo selection with no matching lines can persist an orphaned, zero-item `AppointmentCombo` | Combos |
| 14 | LOW | Client-side discount preview can diverge from the saved total by ±₹0.01 in rare cases | Frontend |
| 15 | LOW / doc | Comparison report shows untagged "All" columns, contradicting CLAUDE.md's documented scope | Reporting / docs |

---

## Findings

### 1. [CRITICAL] `TagService.delete()` has no guard against deleting the "Commission"/"Bonus" tags
**Files:** `service/TagService.java:122-137`, `controller/TagController.java:68-77`, `templates/tags/list.html:62-67`
**Confidence: CONFIRMED**

`rename()` and `merge()` both call `assertNotCommissionOrBonus()` — the protection `Bug_Report_v1` #9 added. `delete()` never calls it, and strips the tag from every service/product before deleting the row outright:

```java
public void delete(Long id) {
    Tag tag = getById(id);
    ...
    tagRepository.delete(tag); // no assertNotCommissionOrBonus() anywhere in this method
}
```

The UI has no restriction either — the delete button renders identically for every tag, including Commission/Bonus.

**Scenario:** Staff deletes the "Commission" tag (e.g. during cleanup, or confusing it with another tag). Every service/product loses the tag, and the row is permanently gone. `AppointmentServiceLineRepository`/`AppointmentProductLineRepository`'s commission queries (`JOIN ... tags t WHERE LOWER(t.name) = LOWER('Commission')`) now match zero lines — every therapist's commission base silently collapses to ₹0 clinic-wide, with no error, until a payroll run looks wrong. Unlike a rename (recoverable), this is a hard delete: recovery means manually recreating the tag with the exact name and re-tagging every affected item by hand, and any report already generated in the interim is wrong with no record of what changed.

**Fix direction:** add `assertNotCommissionOrBonus(tag.getName(), "deleted")` at the top of `TagService.delete()`; hide/disable the delete button for those two tags in `tags/list.html` as a UX backstop.

---

### 2. [HIGH] Stale page-load baseline on the edit form causes silent lost-update of payments and wrong wallet reversal on concurrent edits
**Files:** `templates/appointments/form.html:374-379, 433-434, 874-887`; `service/AppointmentService.java:617-618, 701-711`
**Confidence: CONFIRMED**

The edit form bakes `EXISTING_AMOUNT_PAID`/`EXISTING_WALLET_APPLIED` into the page as static JS constants at render time. Neither is re-validated against the server's current state at submit — both money fields are computed client-side against that stale baseline and sent as a new **target** value, which the server trusts.

**Variant A — prepaid correction.** `prepaidCorrectionInput` (`name="prepaidCorrection"`) is only visually hidden (`d-none`), never `disabled`, so it's part of every edit POST regardless of whether the pencil-edit was used. `AppointmentService.java:617-618`:
```java
BigDecimal prepaidBase = form.getPrepaidCorrection() != null
        ? form.getPrepaidCorrection() : existing.getAmountPaid();
```
Since the field is always non-null, the fresh-DB fallback never fires. **Scenario:** Appointment #55, `grandTotal=₹2000`, `amountPaid=₹500`. Staff A opens Edit at 10:00 (page snapshots `amountPaid=500`). Staff B independently edits the same appointment at 10:05, recording another ₹500 cash payment (`amountPaid` now `₹1000`, committed). At 10:10 Staff A, unaware, types ₹300 into "Payment Received Now" and submits. Server computes `prepaidBase = 500(stale) + 300 = 800` — Staff B's ₹500 payment is silently erased, with no error and no audit trail.

**Variant B — wallet "Additional from Wallet Now".** `computeWalletApplied()` builds the submitted target as `EXISTING_WALLET_APPLIED(stale) + additionalInput.value`. Server-side, `walletDelta = walletRequested − existing.getWalletAmountApplied()(fresh)`. **Scenario:** `walletAmountApplied=₹200` when Staff A opens Edit. Staff B changes it to ₹500 in a separate session (committed, wallet correctly debited an extra ₹300). Staff A types ₹100 into "Additional from Wallet Now" (intending 200+100=300) and submits: client target = `200+100=300`; server `walletDelta = 300 − 500 = −200`, triggering an unwanted ₹200 wallet **credit** and silently reducing Staff B's legitimate ₹500 usage down to ₹300.

Neither variant trips `Appointment`'s `@Version` check — each transaction is loaded fresh and non-overlapping; the corruption is purely from trusting a client-precomputed target built on stale data.

**Fix direction:** submit the baseline values as hidden fields and have the server reject (same "someone else already updated this" pattern used elsewhere) if they don't match `existing`'s current values, or make both boxes genuinely delta-based server-side rather than client-precomputed targets.

---

### 3. [HIGH] Per-line therapist reassignment bypasses `Appointment`'s optimistic lock — can be silently overwritten by a concurrent full edit
**Files:** `service/AppointmentService.java:748-795` (`reassignServiceLineTherapist`/`reassignProductLineTherapist`), `:626-692` (`updateAppointment`'s clear-and-rebuild), `entity/AppointmentServiceLine.java`/`AppointmentProductLine.java` (no `@Version`)
**Confidence: CONFIRMED (code-traced), scenario PLAUSIBLE**

Reassignment writes directly via `appointmentServiceLineRepository.save(line)`/`...ProductLineRepository.save(line)` — it never loads or saves the parent `Appointment`, so it never touches `Appointment.version`. But `updateAppointment` clears and fully rebuilds `serviceLines`/`productLines` from the submitted form every time it saves, guarded only by `Appointment.version` via `saveWithConflictCheck`.

**Scenario:** Staff A reassigns service line #5 (Commission-tagged) from Therapist X to Therapist Y via the detail page. Staff B, who opened the edit form before A's change and still holds stale line data referencing X, submits shortly after. `updateAppointment` clears line #5 and recreates it from B's stale form — still therapist X — with **no version conflict raised**, since the reassignment never bumped `Appointment.version`. Y's reassignment is silently discarded with no warning to either party, and commission for that line reverts to X unnoticed. This is exactly the class of bug the task asked to look for: two features (`Bug_Report_v2` #1's `@Version` fix, and #3's per-line reassignment conflict-check) each correct in isolation, not accounting for each other.

**Fix direction:** have the reassignment path also touch/version-check the parent `Appointment` (e.g. a touch-save bumping its version), or serialize reassignment against `updateAppointment` via a shared lock.

---

### 4. [HIGH] Stock is never re-validated at `markAsCompleted`, the only point it's actually consumed
**Files:** `service/AppointmentService.java:369-507` (create/update stock checks), `:512-535` (`markAsCompleted`)
**Confidence: CONFIRMED**

This is a gap introduced by `Bug_Report_v1` #3's fix, which correctly moved stock *decrement* from booking-time to completion-time but added no compensating re-check at the point stock is actually consumed:

```java
// markAsCompleted
product.setStockQuantity(product.getStockQuantity() - pl.getQuantity()); // no availability guard
```

unlike the explicit, friendly check that exists in `createAppointment`/`updateAppointment`.

**Cross-appointment scenario:** Product "Massage Oil" has `stockQuantity=3`. Appointment A books 3 units (create-time check passes, stock untouched — no longer decremented at booking). Appointment B, booked before A completes, also books 3 units — its independent check passes too, since A never touched the DB value. Both are valid `SCHEDULED` appointments jointly demanding 6 units of a 3-unit stock. Completing A: `3-3=0`. Completing B: `0-3=-3`.
**Same-appointment scenario:** the same product added twice on one appointment (standalone + via a combo) at qty 3+3 against stock=5 passes both per-line checks independently, since neither decrements while checking.

`Product.stockQuantity` does carry `@Min(0)` and Bean-Validation-on-flush is active, so a negative value will likely throw and roll back the completion — but that's an incidental side effect of an unrelated annotation, not a designed safeguard, and if it's ever weakened this becomes a silent negative-stock write. Either way, the oversold appointment can never be completed, with only a generic, unfriendly error (no product name, no "available: X", no UI path to resolve short of editing the line out). No test exercises `markAsCompleted` at all.

**Fix direction:** re-check aggregate demand per product against live stock inside `markAsCompleted`'s loop, throwing the same friendly `IllegalArgumentException` shape `createAppointment` uses; also sum per-product demand within a single submission before comparing to stock in `createAppointment`/`updateAppointment`.

---

### 5. [HIGH] Actual Revenue report folds non-COMPLETED "advance payment" amounts into the COMPLETED-only summary cards
**File:** `service/RevenueReportAggregator.java:94-160` (`buildSummary`, `advanceSpec`)
**Confidence: CONFIRMED**

`buildSummary` explicitly adds `amountPaid` from **SCHEDULED/CANCELLED/NO_SHOW** appointments ("advance received") into both `netRevenue` and `collected`, regardless of the status filter selected — there's no way to view these cards without this contamination. This contradicts CLAUDE.md ("the summary cards/totals only ever count `COMPLETED` appointments") and the feature's own requirements doc, which states `Net Revenue = Gross Revenue − Combo Discounts − Manual Discounts` should hold exactly.

**Scenario:** date range with one COMPLETED appointment (`grandTotal=₹5000`, fully paid) and one SCHEDULED appointment in the same range (`grandTotal=₹2000`, ₹800 paid as advance). Report shows Gross Revenue = ₹5000, discounts = ₹0, but **Net Revenue = ₹5800** — the identity breaks by exactly the advance amount, while "Completed Appointments" still says 1. The "Revenue by Therapist"/"by Service" breakdowns are scoped to completed-only, so the summary card and the breakdown tables on the same page no longer reconcile whenever any advance exists. This also breaks with the wallet feature's own "revenue recognized at completion" principle — a wallet-funded SCHEDULED appointment correctly isn't counted, but a cash-funded SCHEDULED appointment now is, an inconsistent double standard. `RevenueReportAggregator` has zero dedicated unit tests (`ReportServiceTests` mocks it out entirely), so none of this math is verified by the suite.

**Fix direction:** drop the advance-folding and keep summary cards strictly COMPLETED-only per the documented spec, moving "Advance Payments" to its own clearly-separate, never-summed card — or, if intentional, update CLAUDE.md/the requirements doc and add a test asserting the (now intentionally relaxed) identity.

---

### 6. [MEDIUM-HIGH] A genuine optimistic-lock conflict still discards the whole submitted form — `Bug_Report_v1` #4's fix is incomplete
**File:** `controller/AppointmentController.java:163-172` (`save`), `:264-273` (`update`)
**Confidence: CONFIRMED**

Both methods only special-case `catch (IllegalArgumentException e)` for the form-preserving re-render added by v1 #4. Everything else — including `IllegalStateException`, which is exactly what `saveWithConflictCheck`/`WalletService.persistBalance` throw for a genuine optimistic-lock conflict — falls into a generic `catch (Exception e)` that flashes a message and redirects to a blank `/appointments/new` or freshly-reloaded `/appointments/{id}/edit`, discarding everything the user entered.

**Scenario:** a patient has ₹500 wallet balance; Staff A and Staff B each try to apply ₹400 from it to two different appointments at nearly the same time. Whichever request loses the `saveAndFlush` race gets `IllegalStateException`, an entirely expected outcome of the concurrency design — but it dumps the losing staff member back to a blank form, forcing a full re-entry instead of just re-checking the wallet amount.

**Fix direction:** `catch (IllegalArgumentException | IllegalStateException e)` in both `save()` and `update()`.

---

### 7. [MEDIUM] `markAsCompleted` doesn't use the shared conflict-safe save path other status transitions use
**File:** `service/AppointmentService.java:512-535`, contrast with `cancelAppointment`/`markAsNoShow`/`updateAppointment` (all via `saveWithConflictCheck`)
**Confidence: CONFIRMED**

`markAsCompleted` calls plain `appointmentRepository.save(appt)` instead of `saveWithConflictCheck`. Hibernate's `@Version` check still applies automatically at flush either way, so no actual double-decrement is possible — a losing concurrent completion still rolls back atomically since the stock and status mutations flush together. But the failure surfaces as a raw Hibernate `ObjectOptimisticLockingFailureException` instead of the friendly message every other write path gives, an inconsistency directly relevant since `markAsCompleted` is the one write path CLAUDE.md's own list of "race-prone write paths" doesn't mention, despite it mutating inventory.

**Fix direction:** route `markAsCompleted`'s save through `saveWithConflictCheck` for consistency.

---

### 8. [MEDIUM] No CSRF protection on any money-mutating endpoint
**Files:** `pom.xml` (no `spring-boot-starter-security` dependency); every mutating form across `appointments/*.html`, `fragments/wallet-modals.html`, `combos/form.html`, etc. is a plain `<form method="post">` with no CSRF token.
**Confidence: CONFIRMED**

The app being unauthenticated by design doesn't make CSRF moot — it's presumably reachable on a clinic LAN, not the public internet, but a staff member's browser (which does have LAN access) visiting any external page can be made to silently auto-submit a hidden form to e.g. `POST /appointments/5/cancel`, `POST /appointments/5/complete`, or `POST /patients/3/wallet/refund` — all money/schedule-mutating — with no visible sign to the victim.

**Fix direction:** add `spring-boot-starter-security` with CSRF enabled (no login required, just the filter + Thymeleaf's Spring Security dialect for `_csrf` hidden inputs), or at minimum a same-origin/Referer check on state-changing POSTs.

---

### 9. [MEDIUM] Per-therapist revenue/comparison reporting groups by therapist full name (string), not ID
**Files:** `repository/AppointmentServiceLineRepository.java:101-121,137-153`, `AppointmentProductLineRepository.java:98-114`; consumed by `RevenueReportAggregator.buildByTherapist` and `ReportService`'s "top therapist" lookup
**Confidence: CONFIRMED (code-traced); impact PLAUSIBLE**

`Therapist.fullName` has no uniqueness constraint. The "revenue by therapist" and "top therapist per service" queries — and `RevenueReportAggregator.mergeRevenue` — key off the name string, not the entity/ID. Two therapists sharing a name (plausible with common names, or a data-entry duplicate) will have their revenue silently merged in the Actual Revenue "by Therapist" table and misattribute "top therapist" in the Performance report. Does **not** affect actual payout math — `CommissionCalculator` correctly queries by therapist entity/ID — so this is a reporting-attribution bug only, not a payroll bug.

**Fix direction:** group by `therapist.id`, join back to the name for display.

---

### 10. [LOW-MEDIUM] Unvalidated open-redirect via `returnUrl` on cancel/complete/no-show/wallet endpoints
**Files:** `controller/AppointmentController.java:287,301,314`; `controller/WalletController.java:35,46,60`
**Confidence: CONFIRMED**

```java
return "redirect:" + (returnUrl.isBlank() ? "/appointments/" + id : returnUrl);
```
`returnUrl` is taken verbatim from the request with no same-origin validation. Combined with #8's lack of CSRF protection, a hostile page can auto-submit `POST /appointments/5/cancel` with `returnUrl=https://evil.example`, cancelling a real appointment and bouncing the browser to an attacker page immediately after, masking that a mutation just happened.

**Fix direction:** validate `returnUrl` starts with `/` and not `//` before using it as a redirect target.

---

### 11. [LOW] Combo lookup queries lack `DISTINCT`, inflating "removed from N combos" flash-message counts
**File:** `repository/ComboRepository.java:32-34` (`findByServiceItems_Service_Id`/`findByProductItems_Product_Id`), consumed by `service/ComboService.java:188-213`
**Confidence: CONFIRMED (code path), impact requires a combo with 2+ rows for the same item**

Unlike the two `@Query` methods in the same file (which use `SELECT DISTINCT`), these derived finders are plain joins. Nothing prevents a combo from having two `ComboServiceItem`/`ComboProductItem` rows for the same item, in which case the combo is returned twice and `CatalogItemRemovalResult.combosAffected` overcounts (e.g. "Removed from 2 combo(s)" for 1 actual combo). No data corruption — Hibernate's identity map returns the same managed instance both times, so the second removal/save is a no-op — just a wrong flash message.

**Fix direction:** add `DISTINCT` to both queries, or dedupe items by service/product id in `ComboService.save()`.

---

### 12. [LOW] `ComboService.save()` doesn't re-check that selected service/product IDs are still active
**File:** `service/ComboService.java:108-131`
**Confidence: PLAUSIBLE (requires a race, not reachable through normal UI)**

`save()` resolves item IDs via plain `findById()` with no active check. A race is possible: staff opens the combo edit form (loads active Service A) → another request deactivates A, stripping it from all *currently existing* combo references → the in-flight edit submits with A still in the payload → `save()` re-adds now-inactive A with no error. The combo becomes non-bookable (`isSelectable()` correctly excludes it) but isn't auto-deactivated until someone re-saves it (self-healing, but confusing). Permanent-delete correctness is unaffected — the exists-check still correctly blocks deleting Service A while this reference exists.

**Fix direction:** validate `.isActive()` in `ComboService.save()`'s item-resolution loop.

---

### 13. [LOW] A combo selection with no matching lines can persist an orphaned, zero-item `AppointmentCombo`
**File:** `service/AppointmentService.java:804-860` (`buildComboSelections`, `applyComboDiscount`)
**Confidence: CONFIRMED**

`buildComboSelections` creates one `AppointmentCombo` per submitted selection unconditionally, without checking any line actually carries that `comboGroupKey`. A malformed JS state or partial submit produces an `AppointmentCombo` with `discountAmount=0`, still persisted and attached — showing a zero-item, ₹0-savings entry in the detail page's "Combo Packages" section. Doesn't corrupt totals (zero contribution everywhere), purely cosmetic/confusing.

**Fix direction:** drop combo selections with no matching lines before attaching them to the appointment.

---

### 14. [LOW] Client-side discount preview can diverge from the saved total by ±₹0.01 in rare cases
**File:** `templates/appointments/form.html:774-852` (`updateTotals`), vs. server's per-phase rounding in `AppointmentService.applyComboDiscount`/`applyDiscount`
**Confidence: CONFIRMED (code-traced), never causes an actual billing error since the server is authoritative**

The server rounds to 2dp at each of the two discount phases, with phase 2's basis being the *sum of already-rounded* phase-1 line totals. The JS preview carries unrounded floating-point values through both phases and only rounds at final display. For combos-plus-manual-discount with fractional percentages, the preview's Grand Total can differ from what's actually saved by ±₹0.01 — a "preview said X, saved Y" surprise, not a real money bug (server always wins).

**Fix direction:** round the discount to 2 decimals immediately after each phase in JS, mirroring the server.

---

### 15. [LOW / documentation] Comparison report shows untagged "All" columns, contradicting CLAUDE.md's documented scope
**Files:** `templates/reports/comparison.html:73-136`, `util/CsvExportUtil.writeTherapistEarnings`, `util/PdfExportUtil.addTherapistEarningsTable`
**Confidence: CONFIRMED**

CLAUDE.md states "the comparison report only shows the tag-filtered figures," but the HTML/CSV/PDF all render the same 14-column table as Daily/Period, including the untagged `all*` figures. Every number shown is individually correct and HTML/CSV/PDF are consistent with each other — this is a doc/implementation mismatch, not a wrong-number bug.

**Fix direction:** either trim the comparison table to tag-filtered columns only, or update CLAUDE.md to describe current behavior.

---

## Verified correct (explicitly checked this pass, no bug found)

- Two-phase discount math and `distributeAmount`'s clamp/redistribution (v2 #5) — traced and consistent, including the 50-line tiny-discount edge case already covered by tests.
- `updateAppointment`'s `editable` gating correctly covers patient/therapist/date/duration and the entire line/discount/combo/wallet rebuild for non-SCHEDULED appointments; no money-field leak found.
- `findConflictsForTherapist`'s ±1-day pre-filter window remains provably safe given `MAX_DURATION_MINUTES`; correctly excludes CANCELLED/NO_SHOW; back-to-back slots correctly don't conflict.
- Permanent-delete reference checks (`TreatmentService`/`ProductService`/`ComboService.permanentlyDelete`) target the correct FK columns, correctly reject active-row deletion via a direct POST (not just a hidden UI button), and run atomically inside one transaction — no orphaning path found.
- Wallet balance can't go negative through any code path; every debit path validates via `compareTo` before mutating. True cross-request concurrency (two staff racing to spend the same wallet balance) is correctly rejected via `PatientWallet.@Version`.
- Wallet ledger completeness holds — every balance mutation has a matching `WalletTransaction`, atomically, across top-up/refund/apply/reverse, including combo-removal-triggered auto-reversal.
- Wallet-modal `patientId` binding is correct on every page it's embedded in, not just the one page previously fixed.
- Pencil-edit mutual exclusivity (v1 #18) genuinely holds — both toggle buttons disable each other, not just their inputs.
- No stored/reflected XSS found anywhere (`th:utext` is unused repo-wide; all `.innerHTML` call sites route free text through an `escHtml()` helper first).
- No price/discount/combo value is ever trusted from the client — every money figure is re-derived server-side from the live catalog on every save.
- No SQL/JPQL injection — the one dynamic query (`PatientRepository` search) uses a bind parameter, not string concatenation.
- Commission/bonus tag filtering, the owner (Marcia) zero-commission short-circuit, discount isolation from commission, and date-range boundary handling are all correct across every report and the dashboard.
- CSV/PDF/HTML numbers are identical for the same filter across all six reports; `PdfExportUtil`'s multi-page footer/ThreadLocal font handling (v2 fixes) holds.

---

## Suggested fix priority

1. **#1** (Commission tag delete) — one-line guard, prevents a clinic-wide silent payroll error.
2. **#4** (stock overselling at completion) and **#3** (reassignment vs. version) — both are real money/inventory correctness gaps under realistic concurrent multi-staff use.
3. **#2** (stale-baseline lost update) — directly loses recorded payments/wallet funds; fix requires a small but deliberate protocol change (hidden baseline fields + server-side match check).
4. **#5** (Actual Revenue advance-folding) — decide the intended business behavior first (this may be a deliberate recent change that just wasn't reconciled with the docs), then fix code or docs accordingly.
5. **#6, #7** — cheap consistency fixes (broaden one catch clause; swap one method call).
6. **#8, #10** (CSRF, open redirect) — worth doing together since they compound; scope/cost depends on whether Spring Security is otherwise wanted in this phase.
7. Remaining LOW items (#9, #11–#15) — low urgency, fix opportunistically.
