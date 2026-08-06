# Bug Report — Full Application Review (v6)

**Date:** 2026-07-27
**Method:** Static code review of the full application. No MySQL/Docker was available in this environment, so this pass — like v1-v5 — is line-by-line code reading against the documented business rules (`CLAUDE.md`) and the detailed requirements docs, not a live browser/DB run. Six focused sub-reviews were run in parallel, each covering one subsystem, then every HIGH/MEDIUM finding below was independently re-traced against the actual source before being included.
**Scope:** Whole application, with deliberate weight on the parts least covered by `Bug_Report_v1.md`–`v5.md`: the brand-new **Expenses / Recurring Expenses / Profit & Loss** module and the new **`THERAPIST_PLUS`** role (added since v5, and still carrying an **uncommitted** local change at review time), plus a full re-verification of appointment money-flow (discounts/combos/packages/wallet), RBAC, reports/exports/calendar, master-data CRUD, and Thymeleaf/JS templates.
**Baseline:** `mvnw compile` (offline) succeeds cleanly on `feature/enhancements` with the working-tree `SecuritySeeder.java` change applied. All findings below are new — v1–v5's findings are not re-litigated except where explicitly noted as "re-verified, still fixed."
**Post-review correction (2026-07-27):** Finding 1 as originally filed ("unreviewed privilege escalation") was incorrect — the product owner confirmed the `SecuritySeeder` change is an intentional requirement change, not a bug. It is kept below, struck through, for traceability, and `requirements/Expenses_Requirements_v1.md` §3.4/§5.5 has been updated with a matching addendum so the spec and the code agree. No action is needed on Finding 1.

**Overall assessment:** the codebase remains unusually disciplined for its size — permanent-delete guards, pagination clamping, tag merge/rename, the two-phase discount engine, wallet's target-vs-delta reversal model, package multiset reconciliation, conflict-detection math, and the v5 security fixes (therapist-deactivation cascade, `returnUrl` sanitization, login rate limiting) were all independently re-derived by hand and found correct. Of the two findings originally flagged as genuine security/data-exposure issues in the newest surface (Expenses + the 5th role), one (Finding 1) turned out to be an intentional, confirmed product decision rather than a bug — see the correction above. **Every other finding — the HIGH data-exposure gap (Finding 2), all 10 MEDIUM findings (3–12), and all 11 LOW findings (13–23) — has now been fixed (2026-07-27)**, verified with `mvnw compile`/`mvnw test` (102/102 passing) after each batch. Finding 1 (not a bug) is the only entry in this report with no code change.

---

## Summary

| # | Severity | Finding | Area | Status |
|---|----------|---------|------|--------|
| 1 | ~~HIGH~~ | ~~Uncommitted `SecuritySeeder` change grants `THERAPIST_PLUS` full catalog CRUD (Services/Products/Combos/Package Templates)~~ | Security / RBAC | **Not a bug** — confirmed intentional requirement change (2026-07-27), see note below |
| 2 | ~~HIGH~~ | ~~`GET /expenses/commission-suggestion` lets any `THERAPIST_PLUS` user read *any other* therapist's computed commission/bonus payout figure, with no ownership check~~ | Security / Expenses | **Fixed** (2026-07-27) |
| 3 | ~~MEDIUM~~ | ~~An appointment line can be both combo-covered **and** package-covered at once — no server guard; the package is debited the line's raw (pre-combo-discount) value~~ | Money correctness / Appointments | **Fixed** (2026-07-27) |
| 4 | ~~MEDIUM~~ | ~~Expense list has no CSV/PDF export at all, despite the spec's explicit acceptance criterion for it~~ | Feature gap / Expenses | **Fixed** (2026-07-27) |
| 5 | ~~MEDIUM~~ | ~~"Salaries & Commission" payout expenses don't require a `therapist` server-side — a crafted request creates an unattributed payout~~ | Data integrity / Expenses | **Fixed** (2026-07-27) |
| 6 | ~~MEDIUM~~ | ~~`RecurringExpenseTemplate` has no `@Version` — concurrent "Generate Now" / scheduler firing can double-generate an expense for the same due period~~ | Concurrency / Expenses | **Fixed** (2026-07-27) |
| 7 | ~~MEDIUM~~ | ~~`RecurringExpenseTemplateForm` never validates `endDate >= startDate`~~ | Validation / Expenses | **Fixed** (2026-07-27) |
| 8 | ~~MEDIUM~~ | ~~Profit & Loss trend is computed **daily**, not monthly as the DTO/spec describe — produces an illegible 300+ point chart over a year range~~ | Reporting | **Fixed** (2026-07-27) |
| 9 | ~~MEDIUM~~ | ~~`PackageTemplate` doesn't cascade service/product deactivation the way `Combo` does — stale templates silently fail at sale time with no admin-facing signal~~ | Data integrity / Packages | **Fixed** (2026-07-27) |
| 10 | ~~MEDIUM~~ | ~~`Combo` has no `@Version` — the "auto-deactivate when left empty" invariant is race-able under concurrent service+product deactivation~~ | Concurrency / Combos | **Fixed** (2026-07-27) |
| 11 | ~~MEDIUM~~ | ~~Cancel modal on the appointment detail page drops `returnUrl` (unlike the Complete/No-Show modals) — breaks back-navigation consistency~~ | UX / Appointments | **Fixed** (2026-07-27) |
| 12 | ~~MEDIUM~~ | ~~Expense form's therapist-picker show/hide is keyed off the literal string `"Salaries & Commission"` in JS, not a stable id/flag — renaming the category silently breaks the feature~~ | UI / Expenses | **Fixed** (2026-07-27) |
| 13 | LOW | `Patient.dateOfBirth` has no `@PastOrPresent` — a future DOB is accepted and produces a negative age | Validation / Patients | Open |
| 14 | LOW | `Therapist.phone`/`email` have no format validation, unlike `Patient`'s equivalents | Validation / Therapists | Open |
| 15 | LOW | `Product.reorderLevel` has no `@Min(0)` | Validation / Products | Open |
| 16 | LOW | `TherapistController` has no search endpoint; `TherapistService.search(String)` is dead code | Consistency / Therapists | Open |
| 17 | LOW | `PdfExportUtil.finish()` has no internal try/finally — a failure there masks the real export error and can leak a stale `ThreadLocal` font/handler | Robustness / Reports | Open |
| 18 | LOW | `DashboardService` unconditionally computes the full P&L aggregator (category breakdown + trend) on every home-page load, for every role, even when the KPI tiles are permission-hidden | Performance / Dashboard | Open |
| 19 | LOW | `validateAggregateStockDemand` doesn't clamp a package-covered line's quantity to 1, risking a spurious "insufficient stock" false rejection | Correctness / Appointments | Open |
| 20 | LOW | Client-side product-quantity stock clamp silently no-ops when the field is cleared (`NaN` comparison) — server-side backstop exists, not exploitable | Frontend / Appointments | Open |
| 21 | LOW | `RecurringExpenseTemplateForm.startDate` is UI-marked read-only on edit but the server accepts changing it via a crafted POST | Validation / Expenses | Open |
| 22 | LOW | `refundModal` fragment is loaded on the appointment detail page with no button that opens it — dead markup | Frontend / Wallet | Open |
| 23 | LOW | `GET /expenses/{id}/edit` doesn't itself check `VOIDED` status (the POST correctly rejects it) — a stale bookmark harmlessly renders a pre-filled edit form for an immutable row | Consistency / Expenses | Open |

---

## Findings

### 1. ~~[HIGH] Uncommitted `SecuritySeeder` change grants THERAPIST_PLUS full catalog CRUD~~ — NOT A BUG

**Resolution (2026-07-27):** confirmed with the product owner that this is an intentional requirement change, not an unreviewed escalation. `requirements/Expenses_Requirements_v1.md` §3.4/§5.5 has been updated with an addendum documenting the widened grant (THERAPIST_PLUS now also gets `VIEW/CREATE/EDIT/DELETE` — not permanent-delete — on Services/Products/Combos/Package Templates, in addition to Expenses). No code change needed; `SecuritySeeder.java`'s own comment already described this grant accurately. Original write-up kept below for traceability only.

<details>
<summary>Original finding (superseded)</summary>

**Files:** `config/SecuritySeeder.java` lines ~205-211 (`seedRolePermissions`) and ~373-376 (`backfillTherapistPlusPermissions`) — this was the **working-tree, uncommitted** diff visible in `git diff` / `git status` at the start of this session.

```java
grant(defaults, THERAPIST_PLUS, SERVICES, VIEW, CREATE, EDIT, DELETE);
grant(defaults, THERAPIST_PLUS, PRODUCTS, VIEW, CREATE, EDIT, DELETE);
grant(defaults, THERAPIST_PLUS, COMBOS, VIEW, CREATE, EDIT, DELETE);
grant(defaults, THERAPIST_PLUS, PACKAGE_TEMPLATES, VIEW, CREATE, EDIT, DELETE);
```

At review time, `requirements/Expenses_Requirements_v1.md` §3.4 read: *"`THERAPIST_PLUS` is scoped identically to `THERAPIST` everywhere else in the app... the **only** difference is Access Matrix grants on the new Expenses-related modules."* — which appeared to contradict the diff above. The doc has since been corrected to reflect the actual intended grant (see the resolution note above), so this is a documentation-lagged-behind-decision situation, not a code defect.

</details>

---

### 2. ~~[HIGH] `GET /expenses/commission-suggestion` leaks any therapist's payout figure to THERAPIST_PLUS~~ — FIXED

**Fix applied (2026-07-27):** `commissionSuggestion` now reads `permissionService.currentTherapistId()` and throws `AccessDeniedException` if it's non-null and doesn't match the requested `therapistId` — OWNER/ADMIN (whose `currentTherapistId()` is always `null`) are unaffected, while THERAPIST_PLUS/THERAPIST can now only ever fetch their own figure. Verified with `mvnw compile`.

<details>
<summary>Original finding</summary>

**File:** `controller/ExpenseController.java` lines 44, 135-143

```java
@RequiresPermission(module = Module.EXPENSES, action = PermissionAction.VIEW)
@GetMapping("/commission-suggestion")
@ResponseBody
public BigDecimal commissionSuggestion(@RequestParam Long therapistId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
    var therapist = therapistRepository.findById(therapistId)
            .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Therapist not found: " + therapistId));
    return commissionCalculator.calculateEarnings(therapist, dateFrom, dateTo).totalVariablePay();
}
```

Gated only by `EXPENSES`/`VIEW`, which `THERAPIST_PLUS` holds — no check that `therapistId` matches the caller's own linked therapist, and no check that the caller even has visibility into the "Salaries & Commission" category (that category is correctly hidden from `THERAPIST_PLUS`'s dropdown via `ExpenseCategoryService.findAllActiveVisible`, but this endpoint sits outside that filtering).

**Scenario:** A `THERAPIST_PLUS` user calls `GET /expenses/commission-suggestion?therapistId=<any other therapist>&dateFrom=...&dateTo=...` directly (devtools, curl, or a modified request) and reads that colleague's computed commission + bonus for any period — exactly the class of figure `requirements/Expenses_Requirements_v1.md` §5.5 says this role must never see ("cannot see Net Revenue, Net Profit, or any P&L summary"). Every other Expense read path (list/search/single-GET/edit) got the restricted-visibility treatment; this one JSON endpoint was missed.

**Fix direction:** since a `THERAPIST_PLUS` can never create a Salaries & Commission expense (category masked), there's no legitimate reason for this role to call the endpoint at all — deny it outright for `THERAPIST_PLUS` (require `REPORTS_PROFIT_LOSS` instead of/in addition to `EXPENSES`/`VIEW`), or scope `therapistId` to `permissionService.currentTherapistId()` when the caller is that role.

</details>

---

### 3. ~~[MEDIUM] A line can be simultaneously combo-covered and package-covered~~ — FIXED

**Fix applied (2026-07-27):** added `AppointmentService.validateNoComboPackageOverlap`, called at the top of both `createAppointment` and `updateAppointment` right after the "at least one service" check — rejects the form with `IllegalArgumentException` if any service or product line has both `comboGroupKey` and `packageItemId` set. Verified with `mvnw test` (102/102 passing).

**File:** `service/AppointmentService.java` lines 536-542, 566-577 (create) and 891-897, 924-932 (update)

```java
int qty = slf.getPackageItemId() != null ? 1 : Math.max(1, slf.getQuantity());
...
AppointmentCombo lineCombo = slf.getComboGroupKey() != null ? comboByGroupKey.get(slf.getComboGroupKey()) : null;
PatientPackageServiceItem packageItem = null;
if (slf.getPackageItemId() != null) {
    packageItem = packageService.resolveServiceItemForConsumption(slf.getPackageItemId(), patient.getId());
    ...
}
```

`comboGroupKey` and `packageItemId` are read and applied completely independently — nothing rejects a line where both are set. Both `Combos_Requirements_v1.md` and `Packages_Requirements_v1.md` state a package-covered line is always added standalone, never through the combo picker (Packages doc §5.12: "never through the Combo picker").

**Scenario:** A crafted POST (not reachable through the documented UI, but reachable via any raw client) sets both fields on one line. The line lands in a combo group (subject to `applyComboDiscount`'s phase-1 discount, shrinking its `discountedLineTotal`/`getEffectiveLineTotal()`) while also being marked package-covered. `packageTotal` is credited from the line's **raw** `lineTotal`, not `getEffectiveLineTotal()` — so the package session is debited for more value than the line actually contributes to `grandTotal` post-discount. This either trips the amount-paid-exceeds-grandTotal guard (fails safe) or, if other lines have headroom, silently overstates what that package session was "worth."

**Fix direction:** reject the form (both create and update) if any line has both `comboGroupKey` and `packageItemId` set; alternatively compute `packageTotal` from `getEffectiveLineTotal()`.

---

### 4. ~~[MEDIUM] Expense list has no CSV/PDF export~~ — FIXED

**Fix applied (2026-07-27):** added `GET /expenses/export-csv`/`.../export-pdf` to `ExpenseController` (gated by `EXPENSES`/`VIEW`, same filters as the list page), `CsvExportUtil.generateExpenseListCsv`/`PdfExportUtil.generateExpenseListPdf`, and CSV/PDF buttons on `templates/expenses/list.html`'s filter bar, matching every other report's export placement. Verified with `mvnw compile`.

**File:** `controller/ExpenseController.java` (whole file — no `export-csv`/`export-pdf` mapping), `service/ExpenseService.java` (no export helper)

`requirements/Expenses_Requirements_v1.md` §6.2/§7 and Acceptance Criterion #10 explicitly require it: *"Profit & Loss **and the Expense list** both support CSV and PDF export, consistent with every other report's export button placement."* Profit & Loss export exists (`ReportController.java`); the raw Expense list export does not — confirmed by grep across the module for `export-csv`/`export-pdf`/`PermissionAction.EXPORT` on `Module.EXPENSES`: zero hits.

**Fix direction:** add `GET /expenses/export-csv` / `.../export-pdf`, reusing `CsvExportUtil`/`PdfExportUtil` the same way every other list/report does, gated by `@RequiresPermission(EXPENSES, ...)`.

---

### 5. ~~[MEDIUM] Therapist not required server-side for a "Salaries & Commission" payout expense~~ — FIXED

**Fix applied (2026-07-27):** added a persisted `ExpenseCategory.payoutCategory` boolean flag (seeded true for "Salaries & Commission", with a `SecuritySeeder.backfillPayoutCategoryFlag` fix-up for pre-existing databases) — `ExpenseService.applyForm` now throws `IllegalArgumentException` if the resolved category is a payout category and `therapistId` is null. Also closes Finding 12 (the flag is what the form's JS now keys off, instead of the category name). Verified with `mvnw test` (102/102 passing).

**File:** `service/ExpenseService.java` lines 99-131 (`applyForm`); UI-only enforcement in `templates/expenses/form.html` (`toggleTherapistGroup`, no `required` on the therapist `<select>`)

Per `requirements/Expenses_Requirements_v1.md` §5.4, a payout expense under the seeded "Salaries & Commission" category must carry a `therapist`. `applyForm` validates `categoryId`/`amount`/`expenseDate` but never checks `therapistId` conditional on the selected category.

**Scenario:** `POST /expenses` with `categoryId=<Salaries & Commission id>` and no `therapistId` is accepted, creating a payout expense with `therapist = null` — defeating the per-therapist payout reconciliation the feature exists for, and silently breaking any future report/filter that expects every row in that category to have a therapist.

**Fix direction:** in `applyForm`, require `therapistId` when the resolved category is the payout category (match by a stable flag, not name — see Finding 12's related point).

---

### 6. ~~[MEDIUM] `RecurringExpenseTemplate` has no `@Version` — "Generate Now" is not concurrency-safe~~ — FIXED

**Fix applied (2026-07-27):** added `@Version` to `RecurringExpenseTemplate`; `generateOne` now calls `recurringExpenseTemplateRepository.saveAndFlush(template)` inside a try/catch that translates `ObjectOptimisticLockingFailureException` into a friendly `IllegalStateException`, mirroring `AppointmentService.saveWithConflictCheck`. A losing concurrent call now fails fast and its `Expense` insert rolls back with it, instead of silently generating a duplicate. Verified with `mvnw test` (102/102 passing).

**File:** `entity/RecurringExpenseTemplate.java` (no `@Version` field, unlike `Expense`/`Appointment`/`PatientWallet`/`PatientPackage`), `service/RecurringExpenseTemplateService.java` lines 171-194 (`generateOne`)

`generateOne` reads `nextDueDate`, persists an `Expense`, then advances and saves the template — with no optimistic-lock guard and no client-side disable-on-click in `recurring-list.html` for the "Generate Now" button.

**Scenario:** two concurrent triggers for the same template — the 01:00 scheduler firing while an admin also clicks "Generate Now," or a double-submitted click — both read the same `nextDueDate`, both persist an `Expense` for that period, and the last write wins on `nextDueDate`. Result: two duplicate expense rows for one due period, contradicting the documented idempotency guarantee ("nextDueDate only advances once per generation"), which in fact only holds for *sequential* calls.

**Fix direction:** add `@Version` to `RecurringExpenseTemplate` and lock it the same way `PackageService.lockAndPersist` does for `PatientPackage`, or use a pessimistic read lock in `generateOne`.

---

### 7. ~~[MEDIUM] No `endDate >= startDate` validation on a recurring template~~ — FIXED

**Fix applied (2026-07-27):** `applyForm` now throws `IllegalArgumentException` when `endDate` is present and before `startDate`, exactly as the fix direction specified. Verified with `mvnw test` (102/102 passing).

**File:** `service/RecurringExpenseTemplateService.java` lines 102-142 (`applyForm`)

Validates label/category/amount/frequency/`startDate` presence but never checks `endDate` isn't before `startDate`. A template can be created with `startDate=2026-08-01`, `endDate=2026-01-01`; it generates once on `startDate`, then immediately auto-deactivates on the next advance — a nonsensical config accepted silently instead of rejected at entry.

**Fix direction:** `if (form.getEndDate() != null && form.getEndDate().isBefore(form.getStartDate())) throw new IllegalArgumentException(...)`.

---

### 8. ~~[MEDIUM] Profit & Loss trend is daily, not the monthly trend the spec/DTO describe~~ — FIXED

**Fix applied (2026-07-27):** `buildTrend` now buckets by `YearMonth` (its own "MMM yyyy" label format, not the shared day-granularity `trendLabelFormat` property used by the Dashboard/standard reports/Actual Revenue trend), so a full-year range now plots ~12 points instead of 365. Verified with `mvnw test` (102/102 passing, including `ProfitLossReportAggregatorTests`).

**File:** `service/ProfitLossReportAggregator.java` lines 66-86 (`buildTrend`) — verified directly:

```java
for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
    BigDecimal revenue = revenueByDay.getOrDefault(day, BigDecimal.ZERO);
    BigDecimal expenses = expensesByDay.getOrDefault(day, BigDecimal.ZERO);
    trend.add(new ProfitLossTrendPointDTO(day.format(trendLabelFormat), revenue, expenses, revenue.subtract(expenses)));
}
```

`requirements/Expenses_Requirements_v1.md` §4.5 defines `trend` as "`{periodLabel, revenue, expenses, profit}` — **month buckets**," and §8 calls it "month-over-month trend." For any range longer than a few weeks (a full-year P&L is the obvious use case for this report), the chart plots one point per day — e.g. 365 points instead of ~12 — contradicting the documented aggregation grain and making the Chart.js trend chart illegible.

**Fix direction:** bucket `revenueByDay`/`expensesByDay` by `YearMonth` instead of `LocalDate`.

---

### 9. ~~[MEDIUM] `PackageTemplate` doesn't cascade catalog-item deactivation like `Combo` does~~ — FIXED

**Fix applied (2026-07-27):** added `PackageTemplateService.handleServiceDeactivated`/`handleProductDeactivated` (with their own `CatalogItemRemovalResult`), mirroring `ComboService`'s exactly — strips the item and auto-deactivates a template left with zero items. `TreatmentService.deactivate`/`ProductService.deactivate` now call both `comboService` and `packageTemplateService` and return a combined flash-message string (their return type changed from `ComboService.CatalogItemRemovalResult` to `String`; the two controller call sites were updated accordingly — no other callers existed). The repository query methods this needed (`findByServiceItems_Service_Id`/`findByProductItems_Product_Id`) already existed in `PackageTemplateRepository`, unused, apparently anticipating this fix. Verified with `mvnw test` (102/102 passing).

**File:** `service/TreatmentService.java` (`deactivate`, ~lines 107-113) / `service/ProductService.java` (`deactivate`, ~lines 112-118) — both call `comboService.handleServiceDeactivated`/`handleProductDeactivated`, but there is no equivalent call into `PackageTemplateService`, and `PackageTemplateService` has no such method at all.

**Scenario:** a service/product bundled into an active `PackageTemplate` is deactivated via `/services/{id}/delete`. Unlike `Combo` (which strips the item and auto-deactivates if left empty), the template keeps referencing the now-inactive row. `PackageTemplateService.computeOriginalPrice`/`computeSuggestedPrice` iterate items and read `.getPrice()` unconditionally, with no `isActive()` check — so the template keeps showing a price built from a stale item and stays listed as active/sellable on `/package-templates` and the patient detail page's "Sell Package" picker. The only place this is actually caught is at sale time, where `PackageService.sellPackage` rejects with `IllegalArgumentException` — so no data corruption occurs, but a template can sit indefinitely looking sellable while every sale attempt from it fails, with no admin-facing signal that it's broken.

**Fix direction:** add `PackageTemplateService.handleServiceDeactivated`/`handleProductDeactivated` mirroring `ComboService`'s, invoked from the same two call sites.

---

### 10. ~~[MEDIUM, plausible] `Combo` has no `@Version` — auto-deactivate-on-empty is race-able~~ — FIXED

**Fix applied (2026-07-27):** added `@Version` to `Combo`. Since the losing race is a child-collection-only mutation (the emptiness check can trip `false` on both concurrent branches without either touching `Combo`'s own row — the same "mutation lives on a mappedBy collection" case `PatientPackage`/`sessionsUsed` already had), a plain `@Version` alone doesn't help; `ComboService.removeFromCombos` now calls a new `lockAndPersist` on every combo touched (not just ones that go empty), which force-increments the version via `entityManager.lock(combo, OPTIMISTIC_FORCE_INCREMENT)` + `flush()`, mirroring `PackageService.lockAndPersist` exactly. This serializes concurrent modifications to the same combo — the losing request now fails with a clear "just updated, please retry" error instead of silently completing. Verified with `mvnw test` (102/102 passing).

**File:** `entity/Combo.java` (no optimistic-lock column, unlike `Appointment`/`PatientWallet`/`PatientPackage` — all deliberately versioned per `CLAUDE.md` for this exact class of problem), `service/ComboService.java` lines 223-255 (`removeFromCombos`)

`removeFromCombos` checks `combo.getServiceItems().isEmpty() && combo.getProductItems().isEmpty()` against the in-transaction snapshot. If a service and a product belonging to the same combo are deactivated in two concurrent requests, each transaction may not see the other's uncommitted removal under MySQL's default REPEATABLE READ — the documented invariant ("a combo can never have zero items while active") can be silently violated: the combo ends with 0 items, still `active = true`.

**Fix direction:** add `@Version` to `Combo` (mirroring `PatientPackage`), or re-check emptiness after both save paths using `entityManager.lock` the way `PackageService.lockAndPersist` does.

---

### 11. ~~[MEDIUM] Cancel modal drops `returnUrl`~~ — FIXED

**Fix applied (2026-07-27):** added the same hidden `returnUrl` input to the Cancel modal's form in `templates/appointments/detail.html` — the controller (`AppointmentController.cancel`) already read and sanitized `returnUrl` correctly, only the template was missing it. Verified with `mvnw compile`.

**File:** `templates/appointments/detail.html` — the Complete modal and No-Show modal both include `<input type="hidden" name="returnUrl" th:if="${returnUrl}" th:value="${returnUrl}">`; the Cancel modal form does not.

**Scenario:** `AppointmentController./{id}/cancel` falls back to `/appointments/{id}` when `returnUrl` is blank, so cancelling an appointment reached from a patient/therapist page always lands back on the bare appointment page instead of the originating page — inconsistent with Complete/No-Show, which correctly preserve navigation context.

**Fix direction:** add the same hidden `returnUrl` input to the Cancel modal form.

---

### 12. ~~[MEDIUM] Expense therapist-picker keyed on a renameable category name~~ — FIXED

**Fix applied (2026-07-27):** fixed together with Finding 5 via the new `ExpenseCategory.payoutCategory` flag — `templates/expenses/form.html`'s category `<option>` now carries `data-payout` (from `c.payoutCategory`) instead of `data-name`, and `isPayoutCategorySelected()` checks `opt.dataset.payout === 'true'` instead of matching the display name. `expense-categories/form.html` gained a "Payout category" checkbox so Owner/Admin can flag/unflag any category. Renaming "Salaries & Commission" no longer breaks the therapist picker. Verified with `mvnw test` (102/102 passing).

**Files:** `templates/expenses/form.html` (`isPayoutCategorySelected()` matches `opt.dataset.name === 'Salaries & Commission'` — a hardcoded literal) vs. `entity/ExpenseCategory.java` (`name` is free-text, editable via `/expense-categories/{id}/edit`, no dedicated "is payout category" flag).

**Scenario:** renaming the seeded "Salaries & Commission" category (an Owner/Admin action explicitly allowed by the category CRUD) silently removes the therapist picker + commission-hint UI for all future entries in that category — no error, and (per Finding 5) no server-side backstop either. Silent UX regression, not data corruption.

**Fix direction:** key off a stable id or a persisted boolean flag instead of the display name; this would also give Finding 5 something reliable to validate against.

---

## Findings 13-23 — LOW severity

**All 11 fixed (2026-07-27).** `mvnw test` 102/102 passing throughout.

| # | File / location | Issue | Scenario | Fix applied | Status |
|---|---|---|---|---|---|
| 13 | `entity/Patient.java:46` | `dateOfBirth` has no `@PastOrPresent` | A future DOB passes validation; `Patient.getAge()` returns a negative `Period` with no guard | Added `@PastOrPresent` | **Fixed** |
| 14 | `entity/Therapist.java:35,37` | `phone`/`email` unvalidated, unlike `Patient`'s equivalents | Garbage values accepted server-side if a client bypasses the form's JS | Mirrored `Patient`'s `@Pattern`/`@Email` onto both fields | **Fixed** |
| 15 | `entity/Product.java:58` | `reorderLevel` has no `@Min(0)` | A negative reorder level distorts `isLowStock()` | Added `@Min(0)` | **Fixed** |
| 16 | `controller/TherapistController.java`, `service/TherapistService.java:36-40` | No `q` search param on the therapist list, unlike every sibling list controller; `TherapistService.search(String)` has zero callers | Inconsistent feature set vs. Patients/Services/Products/Combos; dead code | Added a paginated, active-agnostic `TherapistService.search(query, pageable)` (new `TherapistRepository.findByFullNameContainingIgnoreCase`), wired a `q` param + search box into `TherapistController`/`therapists/list.html`, mirroring `TreatmentController`'s `(showInactive && !hasFilter) ? findAllIncludingInactive(...) : search(...)` pattern. The old unpaginated `search(String)` is unchanged (still used elsewhere as-is) | **Fixed** |
| 17 | `util/PdfExportUtil.java:315-325` (`finish`) | No try/finally around `writeTotalPageCount`/`document.close()`/ThreadLocal cleanup | If either call throws, the real export error is masked and a stale `PdfFont`/`FooterEventHandler` reference stays pinned until the next export on that thread | Wrapped `finish()`'s body in try/finally | **Fixed** |
| 18 | `service/DashboardService.java:42-55` | Full `ProfitLossReportAggregator.getProfitLossReport` (category breakdown + trend) runs on every dashboard load for every role | Wasted query work on the highest-traffic page for roles that never see the resulting tiles (correctly permission-hidden in `dashboard.html`) | `getTodayKPIs` now checks `permissionService.has(REPORTS_PROFIT_LOSS, VIEW)` and skips the aggregator call entirely (zeros returned, never read by the template) when the caller lacks it | **Fixed** |
| 19 | `service/AppointmentService.java` (`validateAggregateStockDemand`) | Uses `Math.max(1, qty)` for every line instead of clamping package-covered lines to 1 like the actual line-building loop does | A stale non-1 quantity alongside a `packageItemId` triggers a spurious "insufficient stock" rejection (over-conservative, fails safe) | Mirrored the `packageItemId != null ? 1 : ...` clamp | **Fixed** |
| 20 | `templates/appointments/form.html` (`onProductQty`) | `parseInt('') > p.stock` is always `false` — clearing the quantity field silently bypasses the client-side over-stock clamp | No functional break — server-side stock validation still applies | Guarded against `NaN` explicitly with `Number.isNaN` instead of relying on the coincidental comparison | **Fixed** |
| 21 | `service/RecurringExpenseTemplateService.java` (`applyForm`) | `startDate` is UI-marked `readonly` on the edit form but the server still calls `setStartDate` unconditionally on update | A crafted POST changes `startDate` post-creation; harmless today since nothing else reads it, but the UI's implied guarantee isn't real | `applyForm` now only sets `startDate` (and seeds `nextDueDate`) inside the existing `template.getId() == null` (create-only) branch | **Fixed** |
| 22 | `templates/appointments/detail.html` | `refundModal` fragment is included but no button opens it | Dead markup shipped on every page load; not a functional break | Removed the unused include (confirmed via `patients/detail.html` that a real trigger button + include pairing is the established pattern, which this page never had) | **Fixed** |
| 23 | `controller/ExpenseController.java` (edit GET) | Doesn't check `status == VOIDED` before rendering the edit form (the POST correctly rejects) | A stale bookmark to a voided expense's edit page renders a pre-filled form that can't actually be submitted successfully | `editForm` now checks `status == VOIDED` and redirects to `/expenses` with a flash error message, mirroring `ExpenseService.update`'s existing check | **Fixed** |

---

## Verified clean (re-derived by hand, not just trusted)

- Two-phase discount engine (`applyComboDiscount` → `applyDiscount`/`distributeDiscount` via `computeEffectiveSubtotal`) — correct on both create and update, including combo add/remove mid-edit.
- `ProportionalAllocator` — single-line, all-zero, all-equal, and mixed-zero/nonzero cases all sum exactly to the target.
- Wallet target-vs-delta auto-reversal — triggers correctly on *every* path that shrinks `grandTotal`, not just cancel/no-show.
- Package multiset reconciliation (`reconcilePackageDelta`) — occurrence-count diff is correct; insufficient-session failures roll back the whole transaction, including already-flushed appointment/package state.
- Conflict-detection ±1-day pre-filter window — independently re-derived against `MAX_DURATION_MINUTES`; no false-negative gap, including at the exact 1440-minute boundary.
- Stock decrement — happens exactly once, only on transition into `COMPLETED`; a `COMPLETED → CANCELLED` transition is structurally impossible.
- `getBalanceDue()`/`getPaymentStatus()` null/zero edge cases match the documented rule exactly.
- Combo price live-recomputation — client never has a price/discount field to trust in the first place.
- Commission/Bonus tag filtering — correctly scoped to the specific line's own service/product, case-insensitive both sides; owner's commission/bonus correctly zeroed while `allServicesRevenue`/etc. stay populated.
- Revenue ↔ Profit & Loss reconciliation — identical date-inclusivity and `COMPLETED`-only basis in both aggregators; figures genuinely reconcile.
- Every PDF export (including the new Profit & Loss one) correctly follows the `END_PAGE` footer-handler pattern that fixed the earlier production multi-page bug — no regression.
- CSV formula-injection protection (`CsvExportUtil.sanitize()`) — present and applied to every free-text export field.
- Calendar twin-event handling — composite ids match server-side fan-out; every mutation path calls `refetchEvents()`, never patches in place.
- Permanent-delete guards on `ClinicService`/`Product`/`Combo`/`PackageTemplate`/`ExpenseCategory` — all check every reference table correctly and require prior deactivation.
- Pagination clamping — present on every paginated controller, including `TagController`/`PackageTemplateController` (CLAUDE.md's own list of controllers is just stale documentation, not a code gap).
- Tag rename/merge/delete — no orphaned join rows, no case-variant duplicate risk.
- v5's three fixes (therapist-deactivation → login cascade, `returnUrl` sanitization via `SafeRedirectUtil`, `LoginRateLimitFilter`) — all re-verified intact; the Expenses feature introduced no new unsanitized `returnUrl` usage.
- CSRF protection — not disabled anywhere in `SecurityConfig`; every form carries a token.
- Access Matrix self-escalation — ADMIN cannot grant itself `ACCESS_MATRIX`/`EDIT` or touch OWNER-role accounts; OWNER's own `ACCESS_MATRIX`/`EDIT` cell can't be revoked.
- `THERAPIST_PLUS` role wiring — "must have therapist FK set" validation, row-level "own data only" scoping helpers, and Access Matrix/role-dropdown enumeration are all enumeration-based and correctly cover the new role with no missed switch/default-case fallthrough (aside from Finding 2).
- `restrictedVisibility` category filtering — consistently applied across list/search/single-GET/edit/void for Expenses, Categories, and Recurring Templates (aside from the one gap in Finding 2).
- No `.?[...]` SpringEL selection-predicate misuse or bare-ternary-branch bug (the two documented historical Thymeleaf footguns) anywhere in the template tree, including the newest Expenses templates.
- No `th:utext` (unescaped output) anywhere in templates.

---

## Test-coverage gaps (noted, not bugs)

- No test exercises a line with both `comboGroupKey` and `packageItemId` set (Finding 3).
- No dedicated `ProportionalAllocator` unit test pinning the hand-verified edge cases.
- No test for `findConflictsForTherapist`'s ±1-day boundary math specifically.
- No test combining combo removal + wallet reversal in one edit (each is tested independently).
- No test for `ExpenseService.search()`'s restricted-category `Specification` scoping (only `getById` is covered).
- No test for the missing "therapist required for Salaries & Commission" rule, or the missing `endDate >= startDate` check (both don't exist yet — Findings 5, 7).
- No concurrency/idempotency test for `generateNow`/scheduler racing (can't be meaningfully written against the current entity — Finding 6).
- No test for `PdfExportUtil`, `CsvExportUtil` (including the CSV-injection `sanitize()` logic), `DashboardService`, or the calendar reschedule/feed endpoints.
- No PDF export smoke test that forces multi-page pagination — the exact regression class the codebase already shipped once in production has no guard rail.

---

## Suggested priority order for fixing

1. ~~**#1**~~ — **no action needed**, confirmed not a bug (see Finding 1's resolution note).
2. ~~**#2**~~ (commission-suggestion data leak) — **fixed** (2026-07-27, see Finding 2).
3. ~~**#3**~~ (combo+package double coverage) — **fixed** (2026-07-27, see Finding 3).
4. ~~**#5, #6, #7, #9, #10**~~ — **fixed** (2026-07-27, see each finding).
5. ~~**#4, #8**~~ — **fixed** (2026-07-27, see each finding).
6. ~~**#11, #12**~~ — **fixed** (2026-07-27, see each finding).
7. ~~**#13-23**~~ (all LOW-severity) — **fixed** (2026-07-27, see the Findings 13-23 table).