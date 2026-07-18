# Bug Report — Full Application Review (v4)

**Date:** 2026-07-18
**Method:** Static code review — four parallel focused passes covering the entire application: (1) security/auth/permissions layer (login, sessions, roles, access matrix, CSRF), (2) core money/state business logic (appointments, wallet, packages, combos, discounts, stock, concurrency), (3) remaining controllers, validation, and error handling (patients, therapists, catalog CRUD, reports, exports, redirects), (4) Thymeleaf templates and frontend JS (XSS, money calculators, modal state, mobile calendar). Each pass read full source, not excerpts, and was instructed to report only concrete, reproducible bugs with a specific failure scenario — not style or hypothetical issues.
**Scope:** Builds on `Bug_Report_v1.md`, `Bug_Report_v2.md`, and `Bug_Report_v3.md`. This pass did **not** re-verify every prior finding line-by-line the way v3 did against v1/v2; it focused on new ground, but two direct overlaps with v3 were confirmed:
- v3 #8 ("No CSRF protection on any money-mutating endpoint") — **RESOLVED** earlier in this session: every plain `<form method="post">` whose `action` is set via JS across the whole template tree (15 forms in 10 files — deactivate/delete modals, cancel-appointment, disable/reset-password user, refund package, rename tag, etc.) now carries an explicit `<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>`. Forms using `th:action` were already safe via `thymeleaf-extras-springsecurity6`'s auto-injection, and all JS `fetch()` POSTs (calendar drag/resize/cancel) already sent the CSRF header.
- v3 #4 ("Stock is never re-validated at `markAsCompleted`") — **PARTIALLY RESOLVED**: a live re-validation check now exists, but see new finding #5 below — the check-then-act sequence has no locking, so it's not safe under concurrent completions.

**This report documents findings only — no fixes have been applied**, except where explicitly noted above as already fixed in this session. Full test suite re-run as a baseline for this pass: build succeeded, no test failures (`mvnw test`, exit code 0).

**Update (same day):** all 21 findings — the two CRITICAL (#1–#2), five HIGH (#3–#7), nine MEDIUM (#8–#16), and five LOW/LOW-MEDIUM (#17–#21) — have since been fixed. See the note at the top of each finding below. Full test suite re-verified green after every fix (73/73 passing; `UserServiceTests` updated for the new `SessionRegistry` constructor dependency, `AppointmentServiceTests` updated for the new atomic `decrementStockIfAvailable` repository call, `PackageServiceTests` gained a new cross-patient-refund test and had its `refund()` call sites updated for the new `patientId` parameter).

---

## Summary

| # | Severity | Finding | Area | Status |
|---|----------|---------|------|--------|
| 1 | CRITICAL | Package refund has no status/amount-consumed guard — the same package can be refunded in full, repeatedly, with no cap | Packages / Money | **FIXED** |
| 2 | CRITICAL | Disabling a user, resetting their password, or changing their role does not invalidate their already-open session | Security / Sessions | **FIXED** |
| 3 | HIGH | Account lockout can be extended indefinitely by an unauthenticated attacker who only knows a username — including the sole OWNER | Security / Auth | **FIXED** |
| 4 | HIGH | THERAPIST role can view/export full clinic-wide revenue and commission-comparison reports, contradicting documented intent | Security / Reports | **FIXED** |
| 5 | HIGH | Stock decrement on `markAsCompleted` is a check-then-act race with no locking — concurrent completions can oversell | Appointment / Inventory | **FIXED** |
| 6 | HIGH | CSV report exports are vulnerable to formula injection (CSV/Excel) via any staff-editable name field | Reports / Export | **FIXED** |
| 7 | HIGH | Package-covered line quantity is not server-clamped to 1 — one session can be made to cover N× its value | Packages / Money | **FIXED** |
| 8 | MEDIUM | Dashboard revenue KPIs/charts are shown to RECEPTIONIST/THERAPIST despite a code comment promising they're hidden | Security / Dashboard | **FIXED** |
| 9 | MEDIUM | `mustChangePassword` flag is set but never enforced — no self-service change-password path exists at all | Security / Auth | **FIXED** |
| 10 | MEDIUM | Combo/Package Template blank name and other entity-level validation failures crash to the generic error page instead of re-rendering the form | Catalog / Validation | **FIXED** |
| 11 | MEDIUM | Combo/Package Template discount value accepts negative numbers unchecked, and a null value with a chosen discount type defers the failure to booking time | Catalog / Validation | **FIXED** |
| 12 | MEDIUM | `permanentlyDelete` for Service/Product does not check Package Template or Patient Package references before allowing hard delete | Catalog / Packages | **FIXED** |
| 13 | MEDIUM | Therapist payroll fields (`commissionRate`, salary, bonus) have no bounds validation | Therapists / Validation | **FIXED** |
| 14 | MEDIUM | Negative `newPaymentAmount` is accepted on appointment **create** (only blocked on update) | Appointment / Money | **FIXED** |
| 15 | MEDIUM | Sell-Package modal keeps stale rows/name/price after Cancel — reopening it can silently merge old and new item rows | Packages / Frontend | **FIXED** |
| 16 | MEDIUM | Documented mobile calendar "listWeek" agenda fallback below 576px does not exist in code | Calendar / Frontend / Docs | **FIXED** |
| 17 | LOW-MEDIUM | Unvalidated `status` query param on the Revenue report (HTML + CSV + PDF) throws instead of failing gracefully like sibling pages | Reports | **FIXED** |
| 18 | LOW | Open redirect via the `Referer` header in `GlobalExceptionHandler`'s fallback-redirect logic | Security | **FIXED** |
| 19 | LOW | Patient/Tag autocomplete search loads the full unpaginated match set into memory before capping to N in Java | Performance | **FIXED** |
| 20 | LOW | Patient `phone`/`email` fields have no format validation (`@Pattern`/`@Email`) | Patients / Validation | **FIXED** |
| 21 | LOW | `PackageController.refund` does not verify the package belongs to the patient ID in the URL | Packages / Authorization | **FIXED** |

---

## Findings

### 1. [CRITICAL] Package refund has no status/amount-consumed guard — repeatable full refund
**Status: FIXED** — `refund()` now rejects any package whose `status == CANCELLED` before computing/paying out a refund, closing both the sequential double-submit case and (via `PatientPackage`'s existing `@Version`) the concurrent-double-refund race, since a racing second flush now hits either the explicit status check or the pre-existing optimistic-lock conflict handler.
**Files:** `service/PackageService.java` — `refund()` (lines ~336-359), `computeRefundableValue()` (lines ~362-377); contrast with `validateConsumable()` (line ~237)

```java
public void refund(Long patientPackageId, BigDecimal amount, PaymentMethod method, String note) {
    if (amount == null || amount.signum() <= 0) throw ...
    if (method == null) throw ...
    PatientPackage pkg = getById(patientPackageId);
    BigDecimal refundable = computeRefundableValue(pkg);   // never checks pkg.getStatus()
    if (amount.compareTo(refundable) > 0) throw ...
    pkg.setStatus(PatientPackageStatus.CANCELLED);
    ...
}
```

`computeRefundableValue` is `Σ priceAllocated × sessionsRemaining/sessionsTotal` — it does not consult `pkg.getStatus()`, and `refund()` never decrements `sessionsRemaining` or tracks a cumulative-refunded total. `WalletService.refund` doesn't have this problem because it debits against a live, decreasing `wallet.balance`; `PatientPackage` has no equivalent field that shrinks as refunds are issued.

**Scenario:** Sell a package — 5 sessions, ₹5,000, 0 used. `POST /patients/{id}/packages/{pkgId}/refund` amount=5000 → succeeds, status flips to `CANCELLED`, one `REFUND` `PackageTransaction` recorded. `sessionsRemaining` is untouched at 5. Call the identical endpoint again — a double-submitted form, a retried request after a slow response, or a deliberate second click — `computeRefundableValue` still returns ₹5,000 (status is never checked, sessions never decremented) → succeeds again → a second ₹5,000 `REFUND` transaction. Repeatable indefinitely. Pure monetary loss, triggerable by an ordinary double-click with no special access or timing needed.

**Fix direction:** reject `refund()` if `pkg.getStatus() != ACTIVE` (mirroring `validateConsumable`'s guard), or maintain and check a cumulative-refunded amount against `computeRefundableValue`'s ceiling regardless of status.

---

### 2. [CRITICAL] Disabling/resetting/role-changing a user does not invalidate their active session
**Status: FIXED** — `SecurityConfig` now registers a `SessionRegistry` bean (plus `HttpSessionEventPublisher` to keep it in sync with real session lifecycle) and wires it into `sessionManagement().sessionRegistry(...)`, which activates `ConcurrentSessionFilter` on every request. `UserService.disable()`, `resetPassword()`, and `update()` (only when the role actually changes) now call a new `invalidateSessionsForUser()` helper that finds every live session for that user's principal and calls `SessionInformation.expireNow()` on it — the next request on that session gets redirected to `/login` instead of continuing under stale authority.
**Files:** `security/PermissionService.java` (`currentRole()`/`currentUserId()`/`currentTherapistId()`), `security/UserPrincipal.java`, `service/UserService.java` (`disable()`, `update()`, `resetPassword()`), `config/SecurityConfig.java` (no `sessionManagement`/`SessionRegistry` configured)

Spring Security only evaluates `UserDetails.isEnabled()`/`isAccountNonLocked()` at authentication time. `UserPrincipal` wraps the `User` row fetched at login and is never re-queried per request; `PermissionService` reads role/id straight off that cached principal. There is no `SessionRegistry` and no forced-logout mechanism anywhere in the app.

**Scenario:** An OWNER/ADMIN disables a user, resets their password after a suspected compromise, or demotes their role. None of these actions touch that user's already-open browser session — they retain full old-role access (booking, cancelling, applying wallet funds, viewing reports, etc.) for up to the 30-minute session timeout configured in `application.yml`. A terminated employee's session, or a demoted user's excess privileges, silently persist past the moment an admin believed they'd cut off access.

**Fix direction:** register a `SessionRegistry`, and on disable/reset-password/role-change explicitly expire that user's active `HttpSession`(s) via `SessionRegistry.getAllSessions(principal, false)` → `expireNow()`.

---

### 3. [HIGH] Unauthenticated account lockout can be extended indefinitely — DoS on any account including OWNER
**Status: FIXED** — `LoginAttemptListener.onFailure` now returns immediately (no counter increment, no `lockedUntil` extension) if the account is already locked and the lock hasn't expired yet. A `LockedException` still fires this listener on every attempt against a locked account, but it's now a no-op instead of pushing `lockedUntil` further into the future — the lock can only be renewed by fresh failures once it naturally expires.
**Files:** `security/LoginAttemptListener.java` (lines ~30-44), `security/UserPrincipal.java::isAccountNonLocked()` (lines ~58-62)

`DaoAuthenticationProvider` throws `LockedException` (an `AbstractAuthenticationFailureEvent` subtype) *before* checking the password, once `isAccountNonLocked()` is already false. `LoginAttemptListener.onFailure` fires on that event too, incrementing `failedLoginAttempts` and resetting `lockedUntil = now + 15min` again — since `attempts >= max` is now permanently true, every subsequent attempt (right or wrong password, doesn't matter) re-locks it for another 15 minutes. There's no distinction between "wrong password" and "already locked," no attempt cap, no IP-based throttling/backoff.

**Scenario:** An attacker who knows only a username (the seeded OWNER username defaults to `"owner"` per `HealingHouseProperties.Security.ownerUsername`) can keep that account permanently locked by POSTing to `/login` periodically — no password knowledge required. This is a trivial, unauthenticated DoS against any account, including the sole OWNER, with no built-in recovery path short of a direct database edit if no second admin account exists.

**Fix direction:** once locked, further failed attempts within the lockout window should not extend `lockedUntil`; add IP-based rate limiting on `/login` independent of account state.

---

### 4. [HIGH] THERAPIST role can view/export unscoped, clinic-wide revenue and commission-comparison reports
**Status: FIXED** — `ReportController` gained a `denyClinicWideReportsForTherapist()` guard (same `permissionService.currentTherapistId() != null` signal `AppointmentController`/`TherapistController` already use for "own data" scoping), called at the top of `daily`/`period`/`comparison`/`patients`/`performance` and all ten of their CSV/PDF export endpoints. A THERAPIST login now gets a 403 on every one of these instead of clinic-wide/other-therapists' figures; their own earnings remain available via `GET /therapists/{id}`. `REPORTS_STANDARD` stays granted to THERAPIST in `SecuritySeeder` (the block is enforced in code, not the Access Matrix) since `REPORTS_REVENUE` was already correctly withheld from that role.
**Files:** `controller/ReportController.java` (`daily`/`period`/`comparison`/`patients`/`performance`, no per-therapist scoping), `config/SecuritySeeder.java` (lines ~152-164, THERAPIST comment: *"own schedule/earnings only... no revenue report"*)

Unlike `AppointmentController`/`TherapistController`, which both implement explicit "own data only" scoping for the THERAPIST role (`enforceOwnAppointmentForTherapist`, `enforceOwnTherapist`), `ReportController`'s standard-report endpoints apply no ownership filtering at all. `SecuritySeeder` nonetheless grants THERAPIST the `REPORTS_STANDARD: VIEW, EXPORT` permission, directly contradicting its own inline comment about intended scope.

**Scenario:** Any THERAPIST-role login can open `/reports/comparison`, select every other therapist, and see/export a side-by-side commission/earnings comparison — clinic-wide revenue, all patients, all therapists' payout figures, downloadable as CSV/PDF. This exposes colleagues' commission data to a peer, which the codebase's own design comment says shouldn't happen.

**Fix direction:** either scope `ReportController` the same way `AppointmentController` scopes THERAPIST access, or split `REPORTS_STANDARD` into a therapist-safe subset vs. a clinic-wide variant gated to OWNER/ADMIN/RECEPTIONIST only.

---

### 5. [HIGH] Stock decrement on `markAsCompleted` is a check-then-act race — no locking
**Status: FIXED** — added `ProductRepository.decrementStockIfAvailable(id, qty)`, a `@Modifying` JPQL `UPDATE ... SET stockQuantity = stockQuantity - :qty WHERE id = :id AND stockQuantity >= :qty`, so the availability check and the decrement are now a single atomic DB statement instead of a Java-side read-then-write. `markAsCompleted` still does the original upfront aggregate-demand pre-check (for a friendly bulk error message in the common case), but the actual decrement loop now calls this atomic method per line and throws if any single call affects 0 rows (meaning stock changed since the pre-check) — the whole completion rolls back via the existing `@Transactional` boundary. Two concurrent completions racing the last unit of stock can no longer both succeed.
**Files:** `service/AppointmentService.java` — `markAsCompleted()` stock recheck/decrement block (lines ~643-667); `repository/ProductRepository.java` (new `decrementStockIfAvailable`); `entity/Product.java` (no `@Version`)

```java
for (AppointmentProductLine pl : appt.getProductLines()) {
    Product product = pl.getProduct();
    int demand = demandByProductId.getOrDefault(product.getId(), 0);
    if (product.getStockQuantity() < demand) throw ...          // read
}
for (AppointmentProductLine pl : appt.getProductLines()) {
    Product product = pl.getProduct();
    product.setStockQuantity(product.getStockQuantity() - pl.getQuantity());  // absolute overwrite, no lock
}
```

`Product` has no `@Version` and no pessimistic lock is taken; the decrement is an absolute-value overwrite (`current - qty`), not an atomic conditional update. A comment above this block claims the check "re-validates live availability right before it's actually consumed" — that guarantee doesn't hold across two concurrent transactions.

**Scenario:** Product X has `stockQuantity = 1`. Two different appointments, each with one line consuming 1 unit of X, both call `markAsCompleted` at nearly the same time in separate transactions. Both read `stockQuantity = 1` before either commits, both pass the `1 < 1` check, both compute `1 - 1 = 0` and write `0`. Net result: stock ends at 0 even though two completions both believed they validly consumed a unit — a silent oversell with no error surfaced to either caller and no log signal distinguishing it from a normal single decrement.

**Fix direction:** add `@Version` to `Product` (mirroring `PatientPackage`), or replace the decrement with a conditional `@Modifying` query (`UPDATE product SET stock_quantity = stock_quantity - :qty WHERE id = :id AND stock_quantity >= :qty`) and check the affected-row count.

---

### 6. [HIGH] CSV report exports are vulnerable to formula injection
**Status: FIXED** — added a `CsvExportUtil.sanitize(String)` helper that prefixes a leading apostrophe onto any cell value starting with `= + - @` (or a leading tab/CR), forcing Excel/Sheets to treat it as text instead of a formula. Applied to every free-text, staff-editable field written to any report CSV: therapist names (earnings, comparison, patient-metrics tables), patient names, service/product names, tag names, and the joined tags column — currency/enum/numeric cells are untouched since they can't carry attacker-chosen content.
**Files:** `util/CsvExportUtil.java` (throughout — every report export path)

Every report CSV writes user-controlled free-text fields — patient name, therapist name, service/product name, tag name — directly into cells via `writer.writeNext(...)` with no sanitization. None of these values are checked for a leading `=`, `+`, `-`, or `@` before being written.

**Scenario:** Any staff member who can create a patient/therapist/service/tag with a name like `=HYPERLINK("http://evil/","x")` or `=cmd|'/c calc'!A1` will have that formula silently execute when the exported CSV is later opened in Excel by whoever reviews the report (owner, accountant, auditor). Classic CSV/formula injection — every name field involved is staff-writable with no format restriction today.

**Fix direction:** prefix any cell value starting with `=`, `+`, `-`, or `@` with a leading `'` (or a single quote/tab) before writing, in a shared helper all export paths funnel through.

---

### 7. [HIGH] Package-covered line quantity is not server-clamped to 1 — revenue leak
**Status: FIXED** — both `createAppointment` and `updateAppointment` now force `qty = 1` server-side whenever a service/product line carries a `packageItemId`, before `lineTotal` is computed (previously `qty` came straight from the client-submitted `Math.max(1, slf.getQuantity())` with no relation to package coverage). `packageAmountApplied`/`amountPaid` can no longer be credited for more than the single session actually deducted from the patient's package.
**Files:** `service/AppointmentService.java` — `createAppointment()` (lines ~508-523), `updateAppointment()` (lines ~853-870); `service/PackageService.java` — `consumeServiceItem`/`consumeProductItem` (lines ~248-297); `entity/AppointmentServiceLine.java` (only `@Min(1)`, no upper bound tied to package coverage)

```java
PatientPackageServiceItem packageItem = null;
if (slf.getPackageItemId() != null) {
    packageItem = packageService.resolveServiceItemForConsumption(slf.getPackageItemId(), patient.getId());
    pendingPackageConsumptions.add(new PendingPackageConsumption(packageItem.getId(), null, lineTotal)); // lineTotal = price * qty
}
```

`consumeServiceItem`/`consumeProductItem` always increment `sessionsUsed` by exactly **1** regardless of the line's `quantity`, but `packageAmountApplied`/`amountPaid` are credited with the line's full `priceAtTime * quantity`. The client (`appointments/form.html`'s `addPackageLine`) always hardcodes quantity to 1 and renders it as a locked/readonly cell — but nothing server-side enforces `quantity == 1` when `packageItemId` is set.

**Scenario:** A direct POST to `/appointments/save` with `serviceLines[0].packageItemId=<valid item id>` and `serviceLines[0].quantity=10` resolves successfully (the item is valid, has sessions remaining), builds a line with `lineTotal = price*10`, consumes exactly 1 session, and credits `packageAmountApplied += price*10` into `amountPaid`. The appointment shows as fully paid for 10 units of the service while only 1 session was deducted from the patient's package — a straightforward revenue leak.

**Fix direction:** when `packageItemId` is set, force/validate `quantity == 1` server-side, matching what the client already assumes.

---

### 8. [MEDIUM] Dashboard revenue is shown to RECEPTIONIST/THERAPIST despite a comment promising it's hidden
**Status: FIXED** — `dashboard.html`'s revenue KPI card, both charts, and the JS block that populates them are now gated behind `th:if="${@perm.has('REPORTS_REVENUE','VIEW')}"` — the same permission `SecuritySeeder` already withholds from RECEPTIONIST/THERAPIST (and grants to OWNER/ADMIN), reused instead of inventing a parallel role check.
**Files:** `controller/DashboardController.java` (no role-based gating beyond `DASHBOARD:VIEW`), `templates/dashboard.html` (no `@perm.has`/role checks — confirmed by full-file review), `config/SecuritySeeder.java` (RECEPTIONIST comment: *"no commission/revenue ₹ visibility (enforced at template level, not this module gate)"*)

The seeder's own comment claims template-level hiding of revenue for RECEPTIONIST. That control does not exist: `dashboard.html` unconditionally renders "Today's Revenue," the 7-day revenue trend chart, and the 30-day revenue-by-tag chart to anyone with `DASHBOARD:VIEW`, which is granted to both RECEPTIONIST and THERAPIST alongside OWNER/ADMIN.

**Scenario:** Any receptionist or therapist login sees full clinic revenue KPIs and trend charts on the home page — a documented access restriction that was never actually implemented.

**Fix direction:** wrap the revenue KPI card and both charts in `dashboard.html` with a role check (`@perm.has` or an explicit role test), matching the intent in `SecuritySeeder`'s comment.

---

### 9. [MEDIUM] `mustChangePassword` is set but never enforced, and no self-service change-password flow exists
**Status: FIXED** — added a self-service `GET/POST /account/change-password` flow (`AccountController`, `UserService.changeOwnPassword` — requires the current password, ≥8 chars, confirmation match) and a new `MustChangePasswordFilter` (registered in `SecurityConfig` via `addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)`) that redirects any authenticated request to that page while the flag is still true, excluding the page itself, login/logout, and static assets. A "Change Password" icon link was added to the navbar for voluntary use too. On success the controller also patches the in-session `UserPrincipal`'s wrapped `User` object directly (it's a different JPA-loaded instance than the one just updated in the DB transaction) so the filter stops redirecting immediately instead of waiting for the flag to naturally resync.
**Files:** `entity/User.java`, `config/SecuritySeeder.java` (set true on owner seed), `service/UserService.java` (set true on create and on reset). Confirmed via repo-wide search: the only other reference is a display badge in `templates/admin/users/list.html`.

The flag is set whenever an account is created or an admin resets its password ("must change on next login"), but nothing — no security filter, no interceptor, no controller — ever reads it to force a change. There is also no self-service "change my password" endpoint anywhere in the codebase for a cooperative user to comply with even if they wanted to.

**Scenario:** A temp/admin-issued password silently becomes permanent. The "Temp PW" badge in the admin user list is purely cosmetic — it never blocks or prompts anything.

**Fix direction:** add a self-service change-password page, and a security filter/interceptor that redirects any authenticated request to that page while `mustChangePassword` is true (excluding the change-password/logout endpoints themselves).

---

### 10. [MEDIUM] Combo/Package Template blank-name (and other bean-validation) failures crash to the generic error page
**Status: FIXED** — `ComboService.save`/`PackageTemplateService.save` now check `StringUtils.hasText(form.getName())` as their first statement and throw `IllegalArgumentException` on a blank name, before anything is persisted. Both controllers' existing `saveAndRedirect` already catches that exception type and re-renders the form with `errorMessage` and all entered data preserved — the fix routes the failure through that existing path rather than letting a blank name reach JPA's flush-time `@NotBlank` check, which threw an uncaught `ConstraintViolationException`.
**Files:** `controller/ComboController.java` (`save`, no `@Valid`/`BindingResult`), `service/ComboService.java` (`save`, lines ~113-170), `controller/PackageTemplateController.java` (`save`, no `@Valid`/`BindingResult`), `service/PackageTemplateService.java` (`save`, lines ~73-128); contrast entities `Combo.java`/`PackageTemplate.java` (both `@NotBlank` on `name`)

`ComboForm`/`PackageTemplateForm` carry no bean-validation annotations, and both controllers bind with plain `@ModelAttribute` — no `@Valid`, no `BindingResult`. The underlying `Combo`/`PackageTemplate` entities *do* have `@NotBlank` on `name`, so a blank name throws `jakarta.validation.ConstraintViolationException` at the repository `save()` call (JPA auto-validates on flush since `spring-boot-starter-validation` is present). The controllers' `saveAndRedirect` only catches `IllegalArgumentException`, so this exception isn't caught locally — it propagates to `GlobalExceptionHandler`'s blanket `Exception` handler, discarding all entered form data and showing the generic 500 `error.html`. Every other entity (Patient/Therapist/Service/Product) properly re-renders its form with field errors via `@Valid` + `BindingResult` instead.

**Scenario:** Staff submits the "New Combo" or "New Package Template" form with an empty name field (or leaves it blank by mistake). Instead of a friendly "Name is required" message and the rest of their entered data preserved, they get the generic error page and lose everything they'd entered.

**Fix direction:** add bean-validation annotations to `ComboForm`/`PackageTemplateForm` mirroring the entity constraints, and switch both controllers to `@Valid @ModelAttribute ... BindingResult` with a form re-render on validation failure.

---

### 11. [MEDIUM] Combo/Package Template discount value accepts negative numbers; null value with a chosen type defers failure to booking time
**Status: FIXED** — both `ComboService.save` and `PackageTemplateService.save` now reject a null `discountValue` whenever `discountType != NONE` ("A discount value is required when a discount type is selected.") and reject `discountValue.signum() < 0` ("Discount value cannot be negative."), before the existing `>100%` percentage check.
**Files:** `service/ComboService.java` (lines ~159-165), `service/PackageTemplateService.java` (lines ~117-123)

```java
DiscountType type = resolveDiscountType(form.getDiscountType());
if (type == DiscountType.PERCENTAGE && form.getDiscountValue() != null
        && form.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
    throw new IllegalArgumentException("Percentage discount cannot exceed 100%.");
}
combo.setDiscountType(type);
combo.setDiscountValue(type == DiscountType.NONE ? null : form.getDiscountValue());
```

Only the ">100%" case is rejected. A negative `discountValue` (FLAT or PERCENTAGE) is accepted with no error and becomes a de-facto surcharge once `applyComboDiscount`/`distributeDiscount` consume it downstream. Separately, if `type != NONE` but `form.getDiscountValue()` is left null, it's stored as `discountType=PERCENTAGE/FLAT, discountValue=null` — the failure (likely NPE or wrong pricing) is deferred from combo-save time to appointment-booking time.

**Fix direction:** reject `discountValue.signum() < 0`, and require a non-null `discountValue` whenever `type != NONE`, at save time in both services.

---

### 12. [MEDIUM] `permanentlyDelete` for Service/Product doesn't check Package Template or Patient Package references
**Status: FIXED** — added `existsByServiceItems_Service_Id`/`existsByProductItems_Product_Id` to `PackageTemplateRepository` (mirroring `ComboRepository`'s existing methods) and `existsByService_Id`/`existsByProduct_Id` to `PatientPackageServiceItemRepository`/`PatientPackageProductItemRepository`. `TreatmentService.permanentlyDelete`/`ProductService.permanentlyDelete` now check both, each with its own specific rejection message ("it is part of one or more package templates" / "...sold patient packages"), inserted after the existing Combo check and before the delete.
**Files:** `service/TreatmentService.java` (`permanentlyDelete`, lines ~119-134), `service/ProductService.java` (`permanentlyDelete`, lines ~124-139); entities `PackageTemplateServiceItem.java`, `PatientPackageServiceItem.java` (both non-nullable FKs to `ClinicService`/`Product`)

```java
public void permanentlyDelete(Long id) {
    ...
    if (appointmentServiceLineRepository.existsByService_Id(id)) { ... }
    if (comboRepository.existsByServiceItems_Service_Id(id)) { ... }
    clinicServiceRepository.delete(service);
}
```

This checks only `AppointmentServiceLine`/`Combo` references, per the pattern documented in CLAUDE.md — but never checks `PackageTemplateServiceItem`/`PackageTemplateProductItem` or `PatientPackageServiceItem`/`PatientPackageProductItem`, both of which hold non-nullable FKs to the same catalog row.

**Scenario:** Because those FKs are `nullable=false`, the DB's own FK constraint currently blocks the delete in practice — but it surfaces as an unhandled `DataIntegrityViolationException`, caught only by `GlobalExceptionHandler`'s generic handler, giving a vague "conflicts with existing data" message instead of the specific, actionable reason ("used in one or more package templates") the code deliberately produces for the two reference types it does check. If the FK constraint is ever weakened (e.g. a future schema change, or `ddl-auto` regenerating differently), this becomes a silent orphaned-FK bug instead of just a bad error message.

**Fix direction:** add the same explicit existence checks for both package item repositories, with a specific rejection message, matching the existing pattern.

---

### 13. [MEDIUM] Therapist payroll fields have no bounds validation
**Status: FIXED** — added `@DecimalMin("0")` to `fixedMonthlySalary`/`performanceBonusAmount`, `@DecimalMin("0")`/`@DecimalMax("1")` to `commissionRate`, and `@Min(0)` to `performanceBonusThreshold` on the `Therapist` entity. `TherapistController.save` already used `@Valid`/`BindingResult` with a form re-render on failure, so this was a pure entity-annotation fix; `therapists/form.html` gained matching `th:errors`/`th:errorclass` feedback for all four fields (previously only `fullName` showed inline errors).
**Files:** `entity/Therapist.java` (`fixedMonthlySalary`, `commissionRate`, `performanceBonusThreshold`, `performanceBonusAmount` — none carry `@DecimalMin`/`@DecimalMax`/`@Min`)

`TherapistController.save` does use `@Valid`, but since these fields carry no constraint annotations, validation is a no-op for them. A negative salary/commission/bonus, or a `commissionRate` typo'd as `50` instead of `0.5` (i.e. 5000% commission), is accepted and flows straight into `CommissionCalculator`.

**Fix direction:** add `@DecimalMin("0")` to salary/bonus amount, `@DecimalMin("0") @DecimalMax("1")` to `commissionRate`, `@Min(0)` to the bonus threshold.

---

### 14. [MEDIUM] Negative `newPaymentAmount` accepted on appointment create (blocked on update)
**Status: FIXED** — `createAppointment` now resolves `newPaymentAmount` into a local variable and throws `IllegalArgumentException("Amount paid cannot be negative.")` on `signum() < 0`, before the `Appointment` is built — the exact same guard `updateAppointment` already had.
**File:** `service/AppointmentService.java` — `createAppointment()` line ~490, contrast `updateAppointment()` lines ~805-807

```java
.amountPaid(form.getNewPaymentAmount() != null ? form.getNewPaymentAmount() : BigDecimal.ZERO)
```

No `signum() < 0` check on create, whereas `updateAppointment()` explicitly has one (`if (prepaidBase.signum() < 0 || newPayment.signum() < 0) throw ...`). `AppointmentForm` has no `@Positive`/`@Min` on `newPaymentAmount`, and the controller doesn't `@Valid`-check the binding.

**Scenario:** POST `/appointments/save` with `newPaymentAmount=-500`. `amountPaid` becomes negative; the only later guard (`amountPaid > grandTotal`) never fires for a negative value. `getBalanceDue()` then overstates what's owed, `getPaymentStatus()` falls through to `"PARTIAL"` instead of `"UNPAID"`, and the Actual Revenue report's `Collected` figure (`Σ amountPaid`) is silently corrupted.

**Fix direction:** add the same `signum() < 0` guard used in `updateAppointment` to `createAppointment`.

---

### 15. [MEDIUM] Sell-Package modal retains stale rows/name/price after Cancel
**Status: FIXED** — added a `hidden.bs.modal` listener on `#sellPackageModal` that removes every `.pkg-service-item-row`/`.pkg-product-item-row`, clears `pkgNameInput`/`pkgTotalPriceInput`, resets `pkgTotalPriceManuallyEdited`, and re-runs `togglePkgUI()`/`updatePkgPreview()` — fires on both Cancel-dismiss and a successful submit's own dismiss (harmless double-clear there, since the page reloads immediately after).
**File:** `templates/patients/detail.html` — `show.bs.modal` handler for `#sellPackageModal` (lines ~804-808)

```js
document.getElementById('sellPackageModal').addEventListener('show.bs.modal', function () {
    pkgTemplateChoices.setChoiceByValue('');
    document.getElementById('pkgSourceTemplateId').value = '';
    pkgTotalPriceManuallyEdited = false;
});
```

This resets only the template picker and a flag — it never clears the dynamically-added service/product item rows (`pkgServiceItemsContainer`/`pkgProductItemsContainer`), `pkgNameInput`, or `pkgTotalPriceInput`. There is no `hidden.bs.modal` listener anywhere in the codebase to fill this gap.

**Scenario:** Staff opens "Sell Package," adds rows and types a name, then clicks Cancel (dismiss without submitting — no page reload happens). Reopening "Sell Package" later for a genuinely new sale still shows the old rows/name. If unnoticed, submitting creates a package with leftover items from the abandoned attempt; if new rows are added on top, `updatePkgPreview()` sums old+new together, silently inflating the auto-filled total price. Only a full page reload (which happens after a successful submit) clears this state.

**Fix direction:** add a `hidden.bs.modal` (or extend the existing `show.bs.modal`) listener that fully resets the item containers, name, and price fields.

---

### 16. [MEDIUM] Documented mobile calendar "listWeek" fallback below 576px doesn't exist in code
**Status: FIXED** — implemented the documented behavior (rather than correcting the docs) in both `calendar.html` and `therapists/calendar.html`: `initialView`/`headerToolbar`/`footerToolbar` are now chosen from a `matchMedia('(max-width: 576px)')` check at calendar construction, and FullCalendar's `windowResize` callback re-evaluates the same check on every resize — moving the view-switcher between the header and a new footer toolbar and calling `calendar.changeView('listWeek')`/`changeView('timeGridDay')` when the breakpoint is crossed live (e.g. a phone rotated to landscape past 576px, or a desktop window resized down).
**Files:** `templates/therapists/calendar.html`, `templates/calendar.html`

CLAUDE.md states: *"Below 576px it switches to a `listWeek` agenda view with the view-switcher moved to a footer toolbar, since the 7-column time grid doesn't fit a phone screen."* No `listWeek`, `matchMedia`, `innerWidth`, or `windowResize` handling exists in either file — both set `footerToolbar: false` unconditionally and `initialView: 'timeGridDay'` with no width-based view switch. CSS only shrinks font sizes/padding under `@media (max-width: 576px)`; it never changes the FullCalendar view. On a phone, switching to Week/Month via the header toolbar (still `dayGridMonth,timeGridWeek,timeGridDay`) renders a cramped multi-column grid with no fallback.

**Scenario:** Either this is a regression (the feature was removed without updating the docs) or it was never implemented as described. Either way, the documented UX doesn't match what ships — worth a decision from the team on whether to implement it or correct the docs.

---

### 17. [LOW-MEDIUM] Unvalidated `status` query param on the Revenue report throws instead of degrading gracefully
**Status: FIXED** — `resolveDrilldownStatus` now wraps `AppointmentStatus.valueOf` in a try/catch and falls back to `COMPLETED` on an unparseable value, matching `PatientController`/`TherapistController`'s existing pattern.
**File:** `controller/ReportController.java` — `resolveDrilldownStatus()` (lines ~186-191), used by the HTML, CSV, and PDF export endpoints (lines ~186, ~405, ~434)

```java
private static AppointmentStatus resolveDrilldownStatus(String status) {
    if (status == null || status.isBlank()) return AppointmentStatus.COMPLETED;
    if ("ALL".equalsIgnoreCase(status.trim())) return null;
    return AppointmentStatus.valueOf(status.trim());
}
```

No try/catch around `valueOf`. `GET /reports/revenue?status=bogus` throws `IllegalArgumentException`, falling through to `GlobalExceptionHandler`'s generic handler → the 500 error page. `PatientController.detail` and `TherapistController.detail` both wrap the identical pattern in try/catch and silently ignore bad values instead.

**Fix direction:** wrap in try/catch (or validate against `AppointmentStatus.values()`) and fall back to the default, matching the sibling controllers.

---

### 18. [LOW] Open redirect via the `Referer` header
**Status: FIXED** — `fallbackUrl` now only honors `Referer` when it's a relative path (starts with a single `/`, not `//`) or same scheme+host as the current request (parsed via `java.net.URI`); anything else — including a malformed value — falls back to `/`.
**File:** `exception/GlobalExceptionHandler.java` — `fallbackUrl()` (lines ~109-112), used by `handleNotFound`/`handleDataIntegrityViolation`/`handleAccessDenied`

```java
private static String fallbackUrl(HttpServletRequest request) {
    String referer = request.getHeader("Referer");
    return (referer != null && !referer.isBlank()) ? referer : "/";
}
```

The `Referer` header is attacker-influenceable — any page that links or auto-POSTs to a URL in this app that triggers one of these exceptions (e.g. a stale `/patients/999999/edit` link) sets `Referer` to a page of the attacker's choosing. Spring's `"redirect:"` prefix does a raw `sendRedirect()` for an absolute target, so the app will 302 to an external URL.

**Fix direction:** only honor `Referer` if it parses as same-origin/relative; otherwise fall back to `/`.

---

### 19. [LOW] Autocomplete search loads the full match set into memory before capping in Java
**Status: FIXED** — added a bounded `PatientService.search(query, limit)` (delegating to the existing paginated `PatientRepository.searchActive(query, Pageable)` via `PageRequest.of(0, limit)`) and a bounded `TagService.search(partial, limit)` (backed by a new `TagRepository.findByNameContainingIgnoreCaseOrderByNameAsc(partial, Pageable)` overload). Both autocomplete controllers now pass the configured `*MaxSuggestions` limit straight into the query instead of fetching everything and calling `.limit(N)` afterward in Java.
**Files:** `controller/PatientController.java::search`, `service/PatientService.java::search(String)` → `PatientRepository.searchActive` (unpaginated); `controller/TagController.java::search`, `service/TagService.java::search(String)` (same pattern)

Both endpoints fetch every matching row via an unpaginated JPQL query, then apply `.limit(N)` in Java afterward, rather than pushing the limit into the query (`Pageable`/`LIMIT`) as the paginated overloads elsewhere in the same services already do.

**Fix direction:** switch both to a `Pageable`-bounded query, capped at the existing `autocomplete.*MaxSuggestions` config value.

---

### 20. [LOW] Patient phone/email have no format validation
**Status: FIXED** — added `@Pattern(regexp = "^$|^[0-9+()\-\s]{7,20}$")` to `phone` and `@Email` to `email` (both still optional — an empty/absent value stays valid). `PatientController.save` already used `@Valid`/`BindingResult` with a form re-render, so this was a pure entity-annotation change; `patients/form.html` gained matching `th:errors`/`th:errorclass` feedback for both fields (previously only `fullName` showed inline errors).
**File:** `entity/Patient.java` (`phone` — unique constraint only, no `@Pattern`; `email` — no `@Email`)

Both accept arbitrary strings; only `phone` uniqueness is DB-enforced. Data-quality issue, not a security bug.

---

### 21. [LOW] `PackageController.refund` doesn't verify the package belongs to the `patientId` in the URL
**Status: FIXED** — `PackageService.refund` now takes `patientId` as its first parameter and throws `IllegalArgumentException` if `pkg.getPatient().getId()` doesn't match, before any status/amount checks run. `PackageController.refund` passes the path-variable `patientId` through.
**File:** `controller/PackageController.java` (`refund`, lines ~43-54); `PackageService.refund` never checks `pkg.getPatient().getId()`

`POST /patients/{patientId}/packages/{packageId}/refund` refunds whatever package `packageId` resolves to — `patientId` is used only for the post-action redirect, never as an ownership filter. Contrast with `PackageController.available`/`WalletController.balance`, which do check therapist-to-patient ownership. Likely low real-world impact today since package refund is presumably gated to roles that can see any patient, but it's inconsistent with the ownership-scoping pattern used elsewhere and worth closing for defense-in-depth.

---

## Areas reviewed and found sound (no bug)

- `ProportionalAllocator.distribute` — clamp-and-redistribute pass correctly keeps per-line shares within bounds and sums exactly to the target.
- `findConflictsForTherapist`'s ±1-day pre-filter vs. `MAX_DURATION_MINUTES` — boundary math correct; touching intervals and self-conflict correctly excluded.
- `reconcilePackageDelta`'s multiset diff — correctly handles an item backing 2+ lines, an item removed entirely, and interaction with per-line therapist reassignment.
- Wallet/package consumption concurrency — `@Version` + `saveAndFlush`/`OPTIMISTIC_FORCE_INCREMENT` correctly serializes concurrent debits/consumption against the same wallet/package aggregate.
- Two-phase discount math (combo discount → whole-appointment discount against the post-combo effective subtotal) — collapses correctly to the single-phase formula with zero combos; always capped at the subtotal/100%.
- `updateAppointment`'s peel-and-reapply of `packageAmountApplied`/`walletAmountApplied` against cumulative `amountPaid` — correctly isolates the cash-only portion, no double-counting found.
- Double-cancel/double-complete races on the same appointment — correctly caught by `Appointment.version` + `saveWithConflictCheck`.
- CSRF: all forms now safe (fixed this session — see Scope note above). All JS `fetch()` POSTs already sent the CSRF header.
- Privilege escalation via user forms: `UserService.requireCanManage` correctly blocks ADMIN from creating/editing/promoting to OWNER; `UserForm` has no mass-assignment surface onto sensitive `User` fields.
- Self-lockout: self-disable is blocked; OWNER accounts can only be touched by another OWNER and can't self-disable — the system can never reach zero OWNERs via the UI.
- IDOR on appointments/therapist profiles: `AppointmentController`/`TherapistController` correctly implement "own data" scoping for THERAPIST — makes findings #4/#21 above stand out as inconsistencies rather than a systemic miss.
- `GlobalExceptionHandler` fails closed on `AccessDeniedException` and unexpected exceptions.
- `PermissionService`/`PermissionView` fail closed on unknown/typo'd module or action names.
- Password hashing: BCrypt; length ≥ 8 enforced server-side on create and reset; no hardcoded secrets beyond the already-documented default-profile dev DB password.
- No XSS found: no `th:utext` usage anywhere in templates; every `innerHTML` call that interpolates user-controlled strings (patient/service/tag names) goes through a consistent `escHtml()`-style escaping helper, or uses `.textContent`/`.value`.
- No SQL/JPQL injection: all search queries are parameterized.
- `PaginationUtil.clampPage`/`clampPageSize` consistently applied across every paginated list/search endpoint reviewed.
- `PdfExportUtil` — writes all text through iText `Paragraph`/`Cell` objects; no formula-injection-equivalent risk (unlike the CSV export, see #6).
- Combo picker modal fully rebuilds its list on every open — no staleness (unlike the Sell-Package modal, see #15).

---

## Suggested priority order for fixing

1. **#1** (repeatable package refund) and **#2** (stale session on disable/reset/role-change) — real, currently-exploitable money/security holes with trivial trigger conditions.
2. **#3** (unauthenticated lockout DoS on OWNER), **#7** (package quantity revenue leak), **#5** (stock oversell race), **#6** (CSV formula injection) — high-impact, moderate effort.
3. **#4** and **#8** (report/dashboard exposure to non-owner roles) — close the gap between documented intent and code; likely a small `@perm.has`/scoping change.
4. Remaining MEDIUM items (#9–#16) — validation/UX correctness, no urgent exploit path but each is a real defect. **All fixed same day as #1–#8, see per-finding Status notes above.**
5. LOW items (#17–#21) — polish and defense-in-depth. **All fixed same day, see per-finding Status notes above.**
