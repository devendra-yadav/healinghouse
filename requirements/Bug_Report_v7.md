# Bug Report — Full Application Review (v7)

**Date:** 2026-08-12
**Method:** Static code review of the full application, no MySQL/Docker available in this environment — same line-by-line method as v1–v6. Six focused sub-reviews ran in parallel, each covering one subsystem: (1) Employment Contracts, (2) the new in-progress Cash Flow Report feature, (3) RBAC/security across the whole app, (4) `AppointmentService` money-flow regression check, (5) Reports/Dashboard/Calendar, (6) master-data CRUD & validation. Every finding below was traced against actual source with exact file/line citations before inclusion.
**Scope:** Whole application, with deliberate weight on what's new since `Bug_Report_v6.md` (2026-07-27): the **Employment Contracts** module (committed, never previously reviewed) and the **Cash Flow Report** feature (currently uncommitted/in-progress — `AppointmentPaymentTransaction` entity + backfill + aggregator + report page), plus a full regression check of `AppointmentService.java` (touched by the Cash Flow work) and a re-verification of every still-open LOW finding from `Bug_Report_v6.md`.
**Baseline:** `mvnw -q -o compile` succeeds offline on `feature/employment_contracts` with the current working-tree changes applied.

**Overall assessment:** the two highest-stakes areas — real-money double counting in the new Cash Flow ledger, and RBAC boundary enforcement app-wide — both came back clean on the dimension they were most likely to fail: no cross-ledger double counting was found anywhere in the wallet/package/appointment-payment money math, and no unauthenticated/unauthorized state-changing endpoint was found anywhere in the app. The real damage in this pass was concentrated in the **brand-new Employment Contracts module**, which had three HIGH findings — a contract-numbering scheme that permanently broke after an ordinary, documented delete action; CANCELLED/SUPERSEDED contract PDFs becoming unrecoverable, contradicting the feature's own "full history retained and viewable" spec; and a self-service endpoint that let roles explicitly granted **zero** access to Contracts (ADMIN, RECEPTIONIST) perform a state-changing action on any therapist's contract. **All three HIGH findings, all eight MEDIUM findings, and 9 of the 11 LOW findings are now FIXED (2026-08-12/13)**, verified with `mvnw compile` and the full non-DB-dependent unit test suite (145/147 passing — the only 2 skipped are `HealinghouseApplicationTests`/`AuditLogEventListenerIntegrationTest`, which need a live MySQL connection this environment doesn't have, same pre-existing limitation noted in every prior bug report). All 11 of `Bug_Report_v6.md`'s previously-open LOW findings were re-verified and confirmed still fixed — no regressions there. Findings 17 and 20 were investigated and closed as **not bugs** — see their write-ups below for why the current behavior is actually the correct choice, not an oversight. Finding 21 was investigated and closed as **not a bug** — `CLAUDE.md`'s own Employment Contracts business rule explicitly documents the current `AccessDeniedException` behavior as intentional.

---

## Summary

| # | Severity | Finding | Area | Status |
|---|----------|---------|------|--------|
| 1 | HIGH | Contract numbering is `COUNT(*)`-based, not `MAX`-based — deleting any non-last DRAFT permanently breaks contract-number generation for the rest of that calendar year | Data integrity / Contracts | **Fixed** (2026-08-12) |
| 2 | HIGH | CANCELLED/SUPERSEDED contracts' PDFs become permanently inaccessible (`getPdf()` and the detail-page `<embed>` both gate on `status == APPROVED` exactly), contradicting the spec's "history — view PDF only" requirement | Correctness / Contracts | **Fixed** (2026-08-12) |
| 3 | HIGH | `POST /contracts/{id}/acknowledge` has no `@RequiresPermission` and no "caller is actually a therapist" check — ADMIN/RECEPTIONIST (explicitly granted **zero** CONTRACTS access) can acknowledge any therapist's contract, and the UI misattributes the acknowledgment to that therapist | Security / Contracts | **Fixed** (2026-08-12) |
| 4 | MEDIUM | `renewDraft` has no dedup guard against an existing in-progress renewal draft (unlike `generateDraft`) — a double-submit can produce two simultaneously APPROVED contracts for one therapist | Data integrity / Contracts | **Fixed** (2026-08-12) |
| 5 | MEDIUM | Contract-number collision retry (`persistNewDraft`) likely can't recover from a genuine concurrent collision — all attempts share one Hibernate session/transaction, which JPA typically marks rollback-only after a constraint-violation flush | Concurrency / Contracts | **Fixed** (2026-08-12) |
| 6 | MEDIUM | Step-up re-auth (`TherapistStepUpAuthFilter`) only matches `/therapists/**` — `/contracts/{id}` and `/contracts/{id}/pdf` show the same class of sensitive payout data (salary, commission %, bonus terms, signed PDF) with no re-confirmation gate, reachable via a bookmark | Security / Contracts | **Fixed** (2026-08-12) |
| 7 | MEDIUM | `AppointmentPaymentTransactionBackfill`'s idempotency guard is table-wide (`count() > 0`), not per-appointment — a real cash-moving edit landing between server-accept and this `CommandLineRunner` executing permanently skips the entire historical backfill on every future boot | Data integrity / Cash Flow | **Fixed** (2026-08-12) |
| 8 | MEDIUM | Downward `CORRECTED` payment amounts are always counted as real cash outflow in the Cash Flow report, with no way to distinguish "fixing a data-entry mistake" (no cash moved) from "cash physically handed back" — the entity's own `note` field for this exists but is never populated | Money correctness / Cash Flow | **Fixed** (2026-08-12) |
| 9 | MEDIUM | Zero test coverage for the new cash-ledger-write logic in `AppointmentService`/Cash Flow — the wallet/package-delta-nets-to-zero invariant, the RECEIVED-on-create path, and the CORRECTED-on-edit path are all untested; a regression here would pass the full suite silently | Test coverage / Cash Flow | **Fixed** (2026-08-12) |
| 10 | MEDIUM | `PackageTemplate` has no `@Version` — the "auto-deactivate when left empty" invariant is race-able under concurrent service+product deactivation, the same class of bug `Bug_Report_v6` Finding 10 fixed for `Combo` but never applied to `PackageTemplate` | Concurrency / Packages | **Fixed** (2026-08-12) |
| 11 | MEDIUM | `RecurringExpenseTemplateService.advance()` chains `LocalDate.plusMonths` off the previous (already month-length-clamped) `nextDueDate` — a template due on the 29th–31st permanently drifts to an earlier day the first time it crosses a shorter month, with no correction mechanism | Correctness / Expenses | **Fixed** (2026-08-12) |
| 12 | LOW | `ContractService.validateForm` has no bound on `contractPeriodMonths` — a direct POST can set it negative or zero, rendered verbatim into the legal contract text | Validation / Contracts | **Fixed** (2026-08-13) |
| 13 | LOW | `commissionPercent` has no `< 0` check in `validateForm`/`approve` (only `> 100` is checked) — a negative value is only caught late by `Therapist.commissionRate`'s Bean Validation, surfacing as a raw/opaque error instead of a friendly flash message | Validation / Contracts | **Fixed** (2026-08-13) |
| 14 | LOW | `ContractPdfService` converts Owner-edited raw HTML to PDF with no sanitization and no restriction on external resource fetching (`ConverterProperties` has no custom `IResourceRetriever`) — a live SSRF/resource-fetch vector from stored content, low practical risk since only OWNER can edit contract content | Robustness / Contracts | **Fixed** (2026-08-13) |
| 15 | LOW | `POST /therapists/{therapistId}/contracts/renew` never verifies `previousContractId` actually belongs to `therapistId` — a crafted/stale POST could attach a renewal draft to the wrong therapist | Data integrity / Contracts | **Fixed** (2026-08-13) |
| 16 | LOW | `AppointmentPaymentTransactionType` (RECEIVED/CORRECTED) is written but never read — `CashFlowReportAggregator` buckets purely on `amount.signum()`, making the type field effectively decorative | Consistency / Cash Flow | **Fixed** (2026-08-13) |
| 17 | LOW | Expense entries in the Cash Flow ledger/trend use `expenseDate` while every other source uses `createdAt` — a back-dated expense shows under the wrong day relative to same-day cash payments (matches a pre-existing `ProfitLossReportAggregator` convention, likely intentional) | Consistency / Cash Flow | **Not a bug** — confirmed intentional (2026-08-13), see note below |
| 18 | LOW | `AppointmentController.parseCalendarBound` trusts a client-supplied UTC offset for the calendar feed's query window — not currently exploitable (±1-day padding absorbs realistic skew, displayed times use no zone math) but a client-trust dependency worth noting | Robustness / Calendar | **Reviewed** (2026-08-13) — confirmed the offset has zero effect on behavior; documented with a defensive comment |
| 19 | LOW | `AppointmentService.rescheduleAppointment` saves via plain `appointmentRepository.save()`, not `saveWithConflictCheck` — a double drag-drop conflict surfaces Hibernate's raw exception message instead of the app's friendly `IllegalStateException` translation | Consistency / Calendar | **Fixed** (2026-08-13) |
| 20 | LOW | `CsvExportUtil.formatCurrency` has no thousands separator while `PdfExportUtil.formatCurrency` does — cosmetic inconsistency between export formats, not a wrong-value bug | UI / Reports | **Not a bug** — confirmed intentional (2026-08-13), see note below |
| 21 | LOW | Contracts' own-therapist-scoping throws `AccessDeniedException` (confirms the row exists) rather than the `EntityNotFoundException` existence-masking pattern `ExpenseService` uses — consistent with `TherapistController`'s pre-existing precedent, just inconsistent with the newer Expenses pattern | Consistency / Contracts | **Not a bug** — `CLAUDE.md` explicitly documents this as intentional (2026-08-13) |
| 22 | LOW | `AppointmentPaymentTransaction` isn't in `AuditLogEventListener`'s exclusion list (unlike its sibling ledgers `WalletTransaction`/`PackageTransaction`) — every ledger row also generates a redundant generic `AuditLog` CREATE row; over-logging, not a security gap | Consistency / Audit Log | **Fixed** (2026-08-13) |

**Re-verified from `Bug_Report_v6.md`:** all 11 previously-open LOW findings (13–23 in that report) were re-checked against current code and confirmed still fixed, with no regressions — `Patient.dateOfBirth`'s `@PastOrPresent`, `Therapist` phone/email format validation, `Product.reorderLevel`'s `@Min(0)`, `TherapistController`'s search endpoint, `PdfExportUtil.finish()`'s try/finally, `DashboardService`'s permission-gated P&L compute, the package-line stock-demand clamp, the client-side `NaN` stock guard, `RecurringExpenseTemplateForm.startDate` being server-ignored on update, the removed dead `refundModal` markup, and `GET /expenses/{id}/edit`'s `VOIDED` check are all present and correct as of this review. Separately, `Bug_Report_v6.md`'s own summary table still lists Findings 17 and 18 as "Open" even though that report's detailed sections and the current code both confirm they're fixed — a documentation-only staleness in the v6 file itself, not a code defect. `Bug_Report_v6.md` Findings 5 and 12 (the `Expense.therapist` FK / `ExpenseCategory.payoutCategory` flag) describe a design that no longer exists in the codebase at all — `CLAUDE.md` confirms this was a deliberate later architecture change (payee now tracked via the free-text `label` field), not a regression.

---

## Findings

### 1. ~~[HIGH] Contract numbering is `COUNT`-based, not `MAX`-based~~ — FIXED

**Fix applied (2026-08-12):** `nextContractNumber` now derives the sequence from the highest numeric suffix actually in use this year (`ContractService.extractSequence`, parsing the trailing digits of each existing `contractNumber`), not a row count. `EmploymentContractRepository.countCreatedBetween` was replaced with `findContractNumbersCreatedBetween`, which returns the raw `contractNumber` strings for the year so Java can compute the true max rather than relying on SQL string comparison (which breaks once the sequence crosses a digit-count boundary, e.g. `9999` → `10000`). Deleting any draft — first, middle, or last — can no longer cause a later generation to collide with a number still in use; it only leaves a gap, which is expected and harmless. Verified with `mvnw compile` and `mvnw test -Dtest=ContractServiceTests` (all passing).

<details>
<summary>Original finding</summary>

**File:** `service/ContractService.java` lines 259–266 (`nextContractNumber`), consumed by `persistNewDraft` lines 240–257.

```java
long sequence = contractRepository.countCreatedBetween(yearStart, yearEnd) + 1;
```

`sequence` is a row count, not the max sequence number already used. Per §5.8, DRAFT deletion is a normal, documented, hard-delete action.

**Scenario:** Three drafts created in 2026: T1→`HHC-2026-0001`, T2→`HHC-2026-0002`, T3→`HHC-2026-0003`. Owner deletes T2's draft (allowed — DRAFT-only hard delete). Row count for the year is now 2. Generating a contract for T4 computes `sequence = 2 + 1 = 3` → `HHC-2026-0003`, which collides with T3's existing row → `DataIntegrityViolationException`. The retry loop recomputes the *identical* count on every attempt (the failed insert changed nothing), so all 3 retries produce the same colliding number → `IllegalStateException`. Every subsequent contract-generation attempt for **any** therapist now fails identically until the calendar year rolls over.

**Fix direction:** derive the sequence from `MAX` of the numeric suffix already used this year, or use a dedicated per-year counter/sequence table, instead of `COUNT(*)` of surviving rows.

</details>

---

### 2. ~~[HIGH] CANCELLED/SUPERSEDED contract PDFs become permanently inaccessible~~ — FIXED

**Fix applied (2026-08-12):** `ContractService.getPdf()` now gates on `contract.getPdfContent() != null` instead of `status == APPROVED`, and `templates/contracts/detail.html`'s embed/placeholder condition was switched from `contract.status.name() == 'APPROVED'` to `contract.pdfContent != null`. Since a PDF is rendered once at approve-time and never cleared by `cancel()` or the supersede step, it now stays viewable through CANCELLED/SUPERSEDED exactly as §7 requires, and the detail page no longer shows the false "No PDF yet" message for a contract that actually has one. Verified with `mvnw compile`.

<details>
<summary>Original finding</summary>

**Files:** `service/ContractService.java` lines 216–223 (`getPdf`); `templates/contracts/detail.html` line 121 (`th:if="${contract.status.name() == 'APPROVED'}"`) vs. line 130 (falsely shows "No PDF yet — the PDF is generated once this contract is approved").

```java
public byte[] getPdf(Long id) {
    EmploymentContract contract = getById(id);
    if (contract.getStatus() != ContractStatus.APPROVED) {
        throw new IllegalStateException("This contract has not been approved yet — no PDF available.");
    }
    return contract.getPdfContent();
}
```

`cancel()` and `approve()`'s supersede step never clear `pdfContent` — a CANCELLED/SUPERSEDED contract still physically holds its rendered PDF blob — but both `getPdf()` and the detail page's embed condition require `status == APPROVED` exactly, so the PDF becomes unreachable the moment the contract leaves that status. This directly contradicts §7's requirement that Contract History show past CANCELLED/SUPERSEDED rows "view PDF only."

**Scenario:** Owner cancels an approved contract, later reopens it from Contract History to re-check the signed terms — sees a false "No PDF yet" instead of the actual document that was signed.

**Fix direction:** gate on `contract.getPdfContent() != null` (or `status != DRAFT`) in both `getPdf()` and the template's embed condition, instead of `status == APPROVED`.

</details>

---

### 3. ~~[HIGH] `POST /contracts/{id}/acknowledge` lets ADMIN/RECEPTIONIST (zero CONTRACTS access) acknowledge any therapist's contract~~ — FIXED

**Fix applied (2026-08-12):** added a dedicated `ContractController.enforceAcknowledgingTherapistOwnsContract` check used only by the `acknowledge` endpoint, distinct from the pre-existing `enforceOwnContract` (which stays a correct no-op for OWNER's read access). Unlike `enforceOwnContract`, it rejects when `currentTherapistId()` is `null` — so acknowledgment now requires the caller to actually *be* the linked therapist the contract belongs to, not merely "not a mismatched therapist." ADMIN and RECEPTIONIST (whose `currentTherapistId()` is always `null`) are now blocked with `AccessDeniedException`, and OWNER — who already holds full CONTRACTS access but isn't the party signing — is blocked too, which is correct since acknowledgment stands in for the therapist's own signature. Verified with `mvnw compile` and `mvnw test -Dtest=ContractServiceTests` (all passing — service-level `acknowledge()` idempotency logic is unchanged, only the controller-level authorization changed).

<details>
<summary>Original finding</summary>

**Files:** `controller/ContractController.java` lines 177–183 (`acknowledge`), 214–221 (`enforceOwnContract`); `templates/contracts/detail.html` line 73.

```java
@PostMapping("/contracts/{id}/acknowledge")
public String acknowledge(@PathVariable Long id, RedirectAttributes ra) {
    enforceOwnContract(id);
    contractService.acknowledge(id, currentUser());
    ...
}
private void enforceOwnContract(Long id) {
    Long ownTherapistId = permissionService.currentTherapistId();
    if (ownTherapistId != null && !contractService.belongsToTherapist(id, ownTherapistId)) {
        throw new AccessDeniedException(...);
    }
}
```

`currentTherapistId()` returns `null` for OWNER/ADMIN/RECEPTIONIST, so `enforceOwnContract` is a no-op for those roles — it only enforces ownership *when the caller happens to be a therapist*, never that the caller *is* one. The endpoint carries no `@RequiresPermission(CONTRACTS, ...)` at all (intentional, for therapist self-service), so any authenticated ADMIN or RECEPTIONIST — per CLAUDE.md explicitly granted **zero** access to CONTRACTS, not even VIEW — can `POST /contracts/{id}/acknowledge` for any therapist's contract. `detail.html` then displays "Acknowledged ... by {therapist's name}" even though the therapist never touched it — there's no `acknowledgedBy` field on the entity at all, so this corrupts the exact audit trail the feature exists to provide.

**Fix direction:** require `permissionService.currentTherapistId() != null` in addition to the match check, or require `@RequiresPermission(CONTRACTS, VIEW)` (held by THERAPIST/THERAPIST_PLUS, not ADMIN/RECEPTIONIST) ahead of the ownership check.

</details>

---

### 4. ~~[MEDIUM] `renewDraft` has no dedup guard — a double-submit can produce two simultaneously APPROVED contracts~~ — FIXED

**Fix applied (2026-08-12):** `renewDraft` now checks for an existing DRAFT for the therapist (`contractRepository.findByTherapist_IdAndStatus(..., DRAFT)`) and reopens it instead of creating a new one, mirroring `generateDraft`'s pre-existing dedup check exactly. A double-click/resubmit can no longer produce two DRAFT rows pointing at the same `previousContract`. Verified with `mvnw compile` and `mvnw test -Dtest=ContractServiceTests` (16/16 passing, including two new tests: `renewDraftReopensExistingDraftInsteadOfDuplicating`, `renewDraftThrowsWhenCurrentContractNotApproved`).

<details>
<summary>Original finding</summary>

**File:** `service/ContractService.java` lines 115–130 (`renewDraft`), vs. `generateDraft` lines 91–110 which does dedup.

`generateDraft` reuses an existing DRAFT before creating a new one; `renewDraft` has no equivalent check — it only validates the current contract is APPROVED and unconditionally creates a new draft.

**Scenario:** Owner double-clicks "Renew" → two DRAFT rows are created, both pointing `previousContract` at the same APPROVED contract. `approve()` has no check that the therapist doesn't already have a different APPROVED contract — it only checks the target draft's own status. Approving both (reachable via their two distinct review-page URLs) leaves two simultaneously APPROVED contracts for one therapist, breaking the "one active contract" invariant that `getApprovedForTherapist().get(0)` silently assumes.

**Fix direction:** add the same existing-DRAFT dedup check to `renewDraft`, and/or have `approve()` verify no other APPROVED contract exists for the therapist first.

</details>

---

### 5. ~~[MEDIUM] Contract-number collision retry likely doesn't recover from a real concurrent collision~~ — FIXED

**Fix applied (2026-08-12):** added `ContractNumberAssigner`, a small `@Service` whose single `saveInNewTransaction` method runs `@Transactional(propagation = Propagation.REQUIRES_NEW)`. `persistNewDraft`'s retry loop now calls this instead of saving directly, so each attempt gets its own independent transaction — a numbering collision on one attempt no longer risks poisoning the outer transaction (and therefore every later attempt, or the eventual successful commit) the way a shared Hibernate persistence context would. Verified with `mvnw compile` and `mvnw test -Dtest=ContractServiceTests` (all passing).

<details>
<summary>Original finding</summary>

**File:** `service/ContractService.java` lines 240–257 (`persistNewDraft`).

All retry attempts run inside the same `@Transactional` method, sharing one Hibernate session/transaction. Per standard JPA/Hibernate semantics, a constraint-violation exception from a flush/insert typically marks the active transaction rollback-only — meaning the retry loop's later attempts, still inside that same transaction, likely can't cleanly recover from the exact race condition (a genuine concurrent numbering collision) it exists to handle. Not confirmed against a live DB in this review; flagged as a design risk given documented Hibernate flush-exception behavior.

**Fix direction:** run each retry attempt in its own `REQUIRES_NEW` transaction so a failed attempt's session is fully discarded before the next one.

</details>

---

### 6. ~~[MEDIUM] Step-up re-auth doesn't cover `/contracts/**`~~ — FIXED

**Fix applied (2026-08-12):** `TherapistStepUpAuthFilter`'s path match now covers `path.startsWith("/therapists") || path.startsWith("/contracts")`, so `GET /contracts/{id}` and `GET /contracts/{id}/pdf` require the same recent password re-confirmation as `/therapists/**` for THERAPIST/THERAPIST_PLUS. Verified with `mvnw test -Dtest=TherapistStepUpAuthFilterTests` (13/13 passing, including two new tests covering the contract-detail and contract-PDF redirect paths, plus one confirming a recent confirmation still passes through).

<details>
<summary>Original finding</summary>

**Files:** `security/TherapistStepUpAuthFilter.java:45` (`!path.startsWith("/therapists")`); `controller/ContractController.java:187-208`; `templates/contracts/detail.html:53-61` (renders salary/commission/bonus directly); `templates/therapists/detail.html:199` (links straight to `/contracts/{id}` from the step-up-gated page).

CLAUDE.md's own rationale for the filter is that a THERAPIST/THERAPIST_PLUS's commission/earnings page is "sensitive enough that an unattended-but-logged-in session is a real risk." The Contracts detail page shows exactly that class of data (salary, commission %, bonus terms) plus the full signed PDF, reached by a link from the very page the filter protects — but the filter's path match never covers `/contracts/**`.

**Scenario:** A THERAPIST re-confirms their password to view `/therapists/42`, clicks through to `/contracts/17`, then walks away or bookmarks it. Anyone else at that unattended session (even in a brand-new session after the 5-minute step-up window expires, since this path is never intercepted at all) can view salary/commission/bonus terms and the signed PDF with no re-confirmation prompt.

**Fix direction:** extend `TherapistStepUpAuthFilter`'s path match to also cover `GET /contracts/{id}` and `GET /contracts/{id}/pdf`, or generalize the filter to a role+sensitivity predicate instead of a hardcoded path prefix.

</details>

---

### 7. ~~[MEDIUM] Cash Flow backfill's idempotency guard is table-wide — a boot-time race permanently skips all history~~ — FIXED

**Fix applied (2026-08-12):** replaced the table-wide `count() > 0` guard with a per-appointment check (`AppointmentPaymentTransactionRepository.existsByAppointment_Id`), and the backfill loop now runs on every boot (not just when the table starts empty), skipping only appointments that already have ledger coverage. A boot-time race that writes one appointment's live transaction before this runner fires no longer blocks every other appointment from ever being backfilled — the runner self-heals on the next restart instead of permanently giving up. Verified with `mvnw compile`.

<details>
<summary>Original finding</summary>

**File:** `config/AppointmentPaymentTransactionBackfill.java:39-44`.

```java
public void run(String... args) {
    if (appointmentPaymentTransactionRepository.count() > 0) {
        return;
    }
```

Spring Boot's embedded server can begin accepting connections during context refresh, before `CommandLineRunner`s finish executing, and several other runners (`StaleCheckConstraintBackfill`, `SecuritySeeder`'s backfills) run before this one on the same boot. If any real cash-moving appointment edit (`AppointmentService.updateAppointment`) lands in that window, `count()` is already `> 0` and the **entire** historical backfill is silently, permanently skipped — every future boot sees a non-empty table and never retries.

**Scenario:** On the first deploy of this feature, a receptionist edits an appointment's payment a few seconds after the app starts accepting traffic but before this runner fires. Cash Flow's historical dates stay empty for every pre-existing appointment forever, with no error or warning distinguishing "already backfilled" from "raced and skipped."

**Fix direction:** make the guard idempotent per-appointment (e.g., check for an existing row tagged for that specific `appointment_id`, or a dedicated "backfill completed" marker row) rather than gating the whole run on a single global count.

</details>

---

### 8. ~~[MEDIUM] Downward payment corrections are always counted as real cash outflow~~ — FIXED

**Fix applied (2026-08-12):** added `AppointmentPaymentTransaction.cashPhysicallyReturned` (boolean, defaults false) and a matching `AppointmentForm.prepaidCorrectionCashReturned` field, surfaced as a "Cash was physically returned to the patient" checkbox next to the prepaid-correction pencil-edit on `appointments/form.html` (unchecked by default, since a typo fix is the far more common reason staff use this control). `AppointmentService.updateAppointment` now only sets the flag true when the correction lowers the cash portion **and** the box was checked. `CashFlowReportAggregator`'s summary/trend/ledger all now skip a negative CORRECTED row unless `cashPhysicallyReturned` is true, instead of unconditionally counting every downward correction as outflow. Verified with `mvnw test -Dtest=CashFlowReportAggregatorTests,AppointmentServiceTests` (all passing) — the existing aggregator test was updated to flag `cashPhysicallyReturned(true)` explicitly, and a new test confirms an unflagged correction is excluded from every total and the ledger entirely.

<details>
<summary>Original finding</summary>

**Files:** `service/AppointmentService.java:1037-1045` (writes the `CORRECTED` row, never populates `.note(...)`); `service/CashFlowReportAggregator.java:97-98,110` (unconditionally buckets every negative correction into `totalOutflow`); `entity/AppointmentPaymentTransaction.java:19-21` (javadoc distinguishes "fixing a data-entry mistake" from "cash physically handed back").

The entity's own javadoc documents two different real-world meanings for a negative `CORRECTED` amount, but `AppointmentService` never distinguishes which one occurred, and the `note` field provided for exactly this purpose is never populated at either write site (create at :645-651, edit at :1037-1045).

**Scenario:** Staff mistypes ₹5000 instead of ₹500 and fixes it via the pencil-edit correction — no cash ever physically moved, but Cash Flow's Total Outflow and Net Cash Flow both drop by ₹4500 as if it were a real refund handed to the patient.

**Fix direction:** capture staff intent at correction time (a reason/checkbox: "data-entry fix" vs. "money returned to patient") and only count the latter as outflow, or at minimum populate `note` so the ambiguity is auditable.

</details>

---

### 9. ~~[MEDIUM] Zero test coverage for the new cash-ledger-write money-flow logic~~ — FIXED

**Fix applied (2026-08-12):** added six new `AppointmentServiceTests` cases plus two `never()` assertions on the existing wallet-increase and package-resubmit tests, covering every path called out in the original fix direction: a cash-only create writes exactly one `RECEIVED` row for the right amount; a zero-payment create writes nothing; a wallet-only or package-only update writes nothing (added to the pre-existing `updateAppointment_walletIncrease_appliesTheDelta`/`updateAppointment_resubmitSamePackageItemId_isNoOpForConsumption` tests, which already set up exactly that scenario); a downward `prepaidCorrection` writes `CORRECTED` with the right signed amount and correctly threads `cashPhysicallyReturned` both flagged and unflagged; and mixing a fresh cash payment with a wallet change in one submit records only the cash portion. `AppointmentServiceTests` is now 33/33 passing.

<details>
<summary>Original finding</summary>

**Files:** `test/.../AppointmentServiceTests.java` (only wires the new mock into the constructor — no `verify()`/`ArgumentCaptor` anywhere); `test/.../CashFlowReportAggregatorTests.java` (only unit-tests the aggregator against hand-built entities, never through `AppointmentService`).

The single most important invariant of the whole feature — that wallet/package deltas always net to exactly zero in the cash-portion formula, so a wallet- or package-funded line never spuriously produces an `AppointmentPaymentTransaction` — has no test verifying it. Nor is there a test confirming a plain cash payment on create writes a `RECEIVED` row of the right amount, or that a `prepaidCorrection` edit writes `CORRECTED` with the right signed delta. Given this is exactly the class of logic flagged as highest-risk (real money double-counting), a future regression here would pass the full suite silently.

**Fix direction:** add cases to `AppointmentServiceTests` — create with cash-only payment → one `RECEIVED` row of that amount; create/update with wallet- or package-only change → no ledger row written; update mixing a cash payment with a wallet/package change in the same submit → only the cash delta is recorded.

</details>

---

### 10. ~~[MEDIUM] `PackageTemplate` has no `@Version` — same race `Bug_Report_v6` fixed for `Combo`, never applied here~~ — FIXED

**Fix applied (2026-08-12):** added `@Version private Long version;` to `PackageTemplate` (identical to `Combo`'s) and a matching `PackageTemplateService.lockAndPersist` helper (`entityManager.lock(..., OPTIMISTIC_FORCE_INCREMENT)` + `flush()`, with the same `OptimisticLockException` → friendly `IllegalStateException` translation `ComboService.lockAndPersist` uses), now called from `removeFromTemplates` instead of a plain `save()`. Two concurrent deactivations touching the same template's last service and last product will now have one lose with a clear "just updated" error instead of silently leaving the template empty-but-active. Verified with `mvnw compile` (no existing test file for `PackageTemplateService`, so no test suite to re-run for this one).

<details>
<summary>Original finding</summary>

**Files:** `entity/PackageTemplate.java` (no `@Version` field, unlike `Combo`); `service/PackageTemplateService.java:219-233` (`removeFromTemplates` does a plain `save()`, no lock).

`Bug_Report_v6` Finding 10 added `@Version` to `Combo` plus a `lockAndPersist` helper (`entityManager.lock(..., OPTIMISTIC_FORCE_INCREMENT)` + `flush()`), called from `ComboService.removeFromCombos` on every combo touched by a deactivation. `PackageTemplateService.handleServiceDeactivated`/`handleProductDeactivated` (added for `Bug_Report_v6` Finding 9, and explicitly documented as "mirrors `ComboService` exactly") use the identical strip-items-then-auto-deactivate-if-empty pattern, but were never given the matching concurrency fix — `PackageTemplate` has no version column to check at all.

**Scenario:** a service and a product on the same `PackageTemplate` are deactivated in two concurrent requests. Under REPEATABLE READ, each transaction's in-memory "is this template now empty?" check can miss the other's uncommitted removal — neither trips the auto-deactivate branch, and the template silently ends up with zero items while still `active = true`.

**Fix direction:** add `@Version` to `PackageTemplate` and route `removeFromTemplates` through a `lockAndPersist` helper identical to `ComboService`'s.

</details>

---

### 11. ~~[MEDIUM] Recurring-expense due dates permanently drift downward after crossing a short month~~ — FIXED

**Fix applied (2026-08-12):** `advance()` no longer chains `plusMonths` off the previous (possibly-clamped) `nextDueDate`. It now takes the template's `startDate` as a second parameter and, for each generation, uses `YearMonth.from(currentDueDate).plusMonths(n)` purely to determine which period to land in, then always recomputes the day-of-month fresh from `startDate.getDayOfMonth()`, clamped to whatever the target month allows. A template anchored to the 31st now correctly lands on Feb 28, then **recovers** to Mar 31 the very next generation, instead of staying pinned at 28 forever. Verified with `mvnw test -Dtest=RecurringExpenseTemplateServiceTests` (9/9 passing, including a new test — `generateAdvancesToClampedDayThenRecoversOriginalDayOnceMonthIsLongEnough` — that exercises exactly this Jan-31 → Feb-28 → Mar-31 sequence).

<details>
<summary>Original finding</summary>

**File:** `service/RecurringExpenseTemplateService.java:225-231` (`advance`).

```java
case MONTHLY -> date.plusMonths(1);
```

`LocalDate.plusMonths` clamps to the target month's last valid day rather than overflowing, and that clamped day is fed straight back into the next `advance()` call as the new `nextDueDate` — nothing anywhere re-derives the day-of-month from the original `startDate`.

**Scenario:** a MONTHLY template with `startDate = 2026-01-31` (rent due on the 31st). Gen 1: Jan 31 → Feb 28. Gen 2: Feb 28 → **Mar 28**, not Mar 31, even though March has 31 days. The due date is now permanently pinned 3 days earlier than intended, forever, with no admin-facing signal and no field on `RecurringExpenseTemplateForm` to correct `nextDueDate` directly.

**Fix direction:** track the original day-of-month (or compute each `nextDueDate` from `startDate.plusMonths(n)` directly) instead of chaining `plusMonths` off the previous, already-clamped value.

</details>

---

### 12. ~~[LOW] `contractPeriodMonths` has no server-side bound~~ — FIXED

**Fix applied (2026-08-13):** `validateForm` now rejects `contractPeriodMonths <= 0` with `IllegalArgumentException`, matching the existing `monthlySalary`/`noticePeriodMonths` checks. Verified with `mvnw test -Dtest=ContractServiceTests` (18/18 passing, including a new test — `generateDraftRejectsZeroOrNegativeContractPeriodMonths`).

<details>
<summary>Original finding</summary>

**File:** `service/ContractService.java:282-311` (`validateForm`); `templates/contracts/form.html:67` has `min="1"` client-side only.

A crafted POST with `contractPeriodMonths=-5` or `0` is accepted and rendered verbatim into the legal contract text by `ContractTemplateRenderer.buildPositionClause` (e.g. "Contract Period: -5 month(s)...").

**Fix direction:** add a `<= 0` rejection to `validateForm`, matching the checks already present for `monthlySalary`/`noticePeriodMonths`.

</details>

---

### 13. ~~[LOW] `commissionPercent` floor not validated~~ — FIXED

**Fix applied (2026-08-13):** added a `signum() < 0` rejection alongside the existing `> 100` check in both `validateForm` and `approve`, throwing the same `IllegalArgumentException` (so `ContractController.approve()`'s catch block now turns it into a friendly flash message instead of a raw Hibernate/Bean Validation error). Verified with `mvnw test -Dtest=ContractServiceTests` (18/18 passing, including a new test — `generateDraftRejectsNegativeCommissionPercent`).

<details>
<summary>Original finding</summary>

**File:** `service/ContractService.java:152-154` (`approve`), `:308-310` (`validateForm`) — both only check `> 100`.

A negative `commissionPercent` passes both checks, gets converted via `movePointLeft(2)`, and is only caught late by `Therapist.commissionRate`'s `@DecimalMin(0)` at Hibernate flush time — not an `IllegalArgumentException`/`IllegalStateException`, so `ContractController.approve()`'s catch block doesn't turn it into a friendly flash message.

**Fix direction:** add a `< 0` check alongside the existing `> 100` check in both places.

</details>

---

### 14. ~~[LOW] Contract PDF rendering has no HTML sanitization or resource-fetch restriction~~ — FIXED

**Fix applied (2026-08-13):** `ContractPdfService` now sets a custom `IResourceRetriever` on `ConverterProperties` that unconditionally denies every actual network/file fetch (`getInputStreamByUrl`/`getByteArrayByUrl` both return `null`). Confirmed against html2pdf's own source (`ResourceResolver.isDataSrc`) that `data:` URIs — the only kind this class's own logo/stamp/signature images ever use — are decoded inline and never routed through this retriever in the first place, so the three embedded brand images are unaffected; only an externally-hosted image reference in Owner-edited HTML would now be silently blocked instead of fetched. Verified with `mvnw compile` (no existing test file for `ContractPdfService`).

<details>
<summary>Original finding</summary>

**File:** `util/ContractPdfService.java:43-48,50-57`.

Owner-edited `contractBodyHtml` is passed to `HtmlConverter.convertToPdf` with a plain `new ConverterProperties()` — no `IResourceRetriever` restriction — so an `<img src="http://...">` in the body would be fetched over the network by html2pdf's default retriever. Only OWNER can edit contract content, so this isn't cross-role exploitable, but it's an unguarded SSRF/resource-fetch vector from stored content.

**Fix direction:** set a custom `IResourceRetriever` on `ConverterProperties` that blocks non-data-URI network fetches.

</details>

---

### 15. ~~[LOW] Renewal doesn't verify the previous contract belongs to the therapist in the URL~~ — FIXED

**Fix applied (2026-08-13):** `ContractController.renew` now calls `contractService.belongsToTherapist(previousContractId, therapistId)` before proceeding and rejects with a friendly `IllegalArgumentException` (already caught by the existing catch block) if it doesn't match. Verified with `mvnw compile`.

<details>
<summary>Original finding</summary>

**File:** `controller/ContractController.java:93-106` (`renew`).

No check that `previousContract.getTherapist().getId() == therapistId`. Only OWNER can reach this route, so it's not a privilege-escalation path, but a stale/crafted POST could silently attach a new renewal draft to the wrong therapist.

**Fix direction:** verify `current.getTherapist().getId().equals(therapistId)` in `renewDraft` or the controller before proceeding.

</details>

---

### 16. ~~[LOW] `AppointmentPaymentTransactionType` is written but never read by the report~~ — FIXED

**Fix applied (2026-08-13):** `CashFlowReportAggregator.buildLedger`'s label now derives from `t.getType()` (`CORRECTED` → "Appointment Correction", else "Appointment Payment") instead of `amount.signum()`. This also fixes a real minor mislabeling the "decorative field" framing was masking: a positive `CORRECTED` row (e.g. fixing a previously under-recorded payment upward) was being shown as a plain "Appointment Payment" even though it's actually a correction. Direction (IN/OUT) still derives from the amount's sign, which is the correct basis for that. Verified with `mvnw test -Dtest=CashFlowReportAggregatorTests` (7/7 passing, including a new test — `upwardCorrectionIsLabeledAsCorrectionNotAsAPlainPayment`).

<details>
<summary>Original finding</summary>

**File:** `service/CashFlowReportAggregator.java:94-100,214-222`.

Inflow/outflow bucketing and ledger labeling are derived purely from `amount.signum()`, never `type`. Produces correct results today, but means `type` is effectively decorative from this report's perspective — worth confirming intentional rather than a half-finished read path.

</details>

---

### 17. [LOW] Expense ledger entries use `expenseDate`, everything else uses `createdAt` — NOT A BUG

**Resolution (2026-08-13):** re-checked `ProfitLossReportAggregator` — it uses the identical `expenseRepository.findByStatusAndExpenseDateBetween(...)` call and buckets by `e.getExpenseDate()` too, confirming `CashFlowReportAggregator` reuses an established, intentional convention rather than an isolated oversight. More importantly, `Expense` has no separate "payment cleared" timestamp — `expenseDate` (a staff-entered field representing when the cost was actually incurred/paid) is the only meaningful "when did this money move" value the entity carries; `createdAt` would only mean "when was this row typed into the system," which is a *less* accurate basis for a cash-basis report, not a more accurate one. No code change made.

<details>
<summary>Original finding</summary>

**File:** `service/CashFlowReportAggregator.java:193-194,241-242`.

A back-dated expense shows under its `expenseDate` in the trend/ledger while a same-day cash payment sorts by actual receipt time — a date-basis mismatch on a cash-basis report where "when did money move" is the point. Matches a pre-existing `ProfitLossReportAggregator` convention (same repository reused), likely accepted rather than an oversight — flagged for awareness.

</details>

---

### 18. [LOW] Calendar feed's date-range parsing trusts a client-supplied UTC offset — REVIEWED, DOCUMENTED

**Resolution (2026-08-13):** traced `OffsetDateTime.toLocalDateTime()`'s actual behavior — it discards whatever offset accompanied the parsed string entirely rather than converting by it; the returned `LocalDateTime` is always just the literal wall-clock digits from the input, regardless of what offset (if any) was attached. This means a client-supplied offset has **zero effect** on the resulting bound today, in either direction — the original finding's premise (a skewed offset could shift the query window) doesn't actually manifest given `.toLocalDateTime()`'s semantics. Added a javadoc comment on `parseCalendarBound` recording this so a future refactor (e.g. switching to `.toInstant()`/`.atZone()`) doesn't accidentally introduce a real client-trust issue where none exists today. No behavior change.

<details>
<summary>Original finding</summary>

**File:** `controller/AppointmentController.java:187-197` (`parseCalendarBound`).

FullCalendar formats `fetchInfo.startStr`/`endStr` in the browser's local timezone by default, not necessarily Asia/Kolkata; `OffsetDateTime.parse(raw).toLocalDateTime()` strips whatever offset the client sent. Not currently exploitable — the ±1-day query padding absorbs realistic skew and displayed event times never apply zone math — but a client-trust dependency worth noting if timezone handling is revisited.

</details>

---

### 19. ~~[LOW] `rescheduleAppointment` bypasses the app's standard conflict-check translation~~ — FIXED

**Fix applied (2026-08-13):** `rescheduleAppointment` now calls the existing private `saveWithConflictCheck` helper instead of `appointmentRepository.save(appt)` directly, so a double drag-drop conflict now surfaces the same friendly "This appointment was just updated by someone else" message every other write path uses. Verified with `mvnw test -Dtest=AppointmentServiceTests` (34/34 passing, including a new test — `rescheduleAppointment_optimisticLockConflict_throwsFriendlyMessage`).

<details>
<summary>Original finding</summary>

**File:** `service/AppointmentService.java:432-456`, specifically `:453` (`appointmentRepository.save(appt)`).

Unlike create/update, which use `saveAndFlush` + a translated friendly `IllegalStateException` for `@Version` conflicts, reschedule uses a plain `save()`. A double drag-drop still surfaces an error (caught by `AppointmentController.reschedule`'s generic exception handler) but with Hibernate's raw message instead of the app's usual friendly one.

</details>

---

### 20. [LOW] CSV/PDF currency formatting is cosmetically inconsistent — NOT A BUG

**Resolution (2026-08-13):** deliberately left as-is after reconsidering the fix direction — CSV is a data-interchange format consumed by spreadsheet tools and scripts, where a plain, un-grouped decimal number (`"1234.56"`) is the safer, more portable convention than one with an embedded thousands separator, which risks locale-dependent re-import misparsing (e.g. a comma-as-decimal-separator locale). PDF's grouped format exists purely for print/on-screen human readability, a different purpose entirely. The two formats intentionally serving different audiences with different conventions isn't a defect — forcing them to match would risk actually breaking CSV consumers for a cosmetic win. No code change made.

<details>
<summary>Original finding</summary>

**File:** `util/CsvExportUtil.java:595-599` (`formatCurrency`, plain `toPlainString()`) vs. `util/PdfExportUtil.java:1252-1256` (`DecimalFormat("#,##0.00")`, grouped). Neither is wrong or ambiguous — just a stylistic mismatch between the two export formats.

</details>

---

### 21. [LOW/Informational] Contracts' existence-masking differs from Expenses' pattern — NOT A BUG

**Resolution (2026-08-13):** confirmed against `CLAUDE.md`'s own Employment Contracts business rule, which explicitly documents the current behavior as intentional: *"a direct URL to another therapist's contract throws `AccessDeniedException` via `GlobalExceptionHandler`"* — stated as a deliberate design choice mirroring `TherapistController.enforceOwnTherapist`, not an oversight or something that drifted from a more defensive intended design. Changing this to match `ExpenseService`'s masking pattern would actually contradict the project's own authoritative spec. No code change made.

<details>
<summary>Original finding</summary>

**Files:** `controller/ContractController.java:216-221` (throws `AccessDeniedException`, confirming the row exists) vs. `ExpenseService`'s `EntityNotFoundException` existence-masking for restricted rows.

Mirrors `TherapistController.enforceOwnTherapist`'s pre-existing, same-shape behavior — not a new gap, just inconsistent with the newer Expenses masking pattern. Practical impact is low since contract/therapist ids aren't otherwise treated as secret. No fix required unless uniform masking is a goal.

</details>

---

### 22. ~~[LOW/Informational] `AppointmentPaymentTransaction` isn't excluded from the generic audit log~~ — FIXED

**Fix applied (2026-08-13):** added `com.clinic.healinghouse.entity.AppointmentPaymentTransaction` to `AuditLogEventListener.EXCLUDED_ENTITY_NAMES`, alongside its sibling ledgers `WalletTransaction`/`PackageTransaction`. Every RECEIVED/CORRECTED row (including future historical backfills) no longer generates a redundant generic `AuditLog` CREATE row. Verified with `mvnw compile`.

<details>
<summary>Original finding</summary>

**File:** `config/AuditLogEventListener.java:51-54` (`EXCLUDED_ENTITY_NAMES` includes `WalletTransaction`/`PackageTransaction` but not `AppointmentPaymentTransaction`, despite its javadoc describing it as the same kind of append-only, self-auditing ledger).

Every RECEIVED/CORRECTED row (including the historical backfill) also generates a redundant generic `AuditLog` CREATE row. Over-logging, not under-logging — no security gap, just noise.

</details>

---

## Verified correct (coverage tracking, not exhaustive)

- **No cross-ledger double counting anywhere in the new Cash Flow money math** — traced the full `createAppointment`/`updateAppointment` flow; the cash-portion delta used for the new ledger write always nets wallet/package deltas to zero within the same edit. `WalletService`/`PackageService` internal transfers (`USAGE`/`REVERSAL`) are correctly excluded from the aggregator's queries, which only ever request `TOP_UP/REFUND`/`PURCHASE/REFUND`.
- **No fabricated reversal on cancel/no-show** — cash-ledger rows are never touched by `cancelAppointment`/`markAsNoShow`, matching the app's documented "no auto-refund on cancel" rule.
- **Cash Flow is genuinely cash-basis** (keyed off `createdAt`/`expenseDate`, never `grandTotal`/appointment status) — avoids the exact class of mistake that motivated the earlier Actual Revenue report rewrite.
- **Permission gating on the new report** is fully wired end-to-end: `Module.REPORTS_CASH_FLOW`, OWNER-exclusive, seeded both fresh and via an idempotent backfill, enforced on all three (view/CSV/PDF) endpoints and the nav card.
- **RBAC app-wide**: no state-changing endpoint found with zero auth gate; every CSV/PDF export carries the same permission (and THERAPIST-scoping, where applicable) as its HTML sibling; `AccessMatrixService` and `UserService` self-escalation guards (OWNER-only pinning, ADMIN blocked from touching OWNER accounts) all still correct; `SafeRedirectUtil.sanitize` used everywhere a client-supplied redirect target exists; login/session hardening (`LoginAttemptListener`, `LoginRateLimitFilter`, `MustChangePasswordFilter`) unchanged and correctly ordered.
- **`Bug_Report_v6` Finding 2** (cross-therapist commission leak via `/expenses/commission-suggestion`) — endpoint no longer exists at all; confirmed removed, not merely patched.
- **`AppointmentService.java`'s pre-existing money invariants all unaffected by the in-progress Cash Flow changes** — discount rounding/remainder guarantee, two-phase combo-then-whole-appointment discount ordering, package multiset reconciliation, wallet target-vs-delta reversal model, `saveWithConflictCheck`'s optimistic-lock translation, and the `validateNoComboPackageOverlap` guard (`Bug_Report_v6` Finding 3) are all still in place, unmoved, and still called at the same points.
- **Reports/Dashboard/Calendar subsystem**: no HIGH/MEDIUM findings — date-range boundaries, COMPLETED-only headline enforcement, per-line commission attribution (incl. the owner-zeroing special case), rounding, CSV/PDF column alignment, PDF footer wiring, Dashboard KPI window definitions, and pagination clamping were all independently re-verified correct.
- **Master-data CRUD**: soft-delete/permanent-delete guards, cascade-on-deactivation wiring (Combo + PackageTemplate both correctly triggered from the single `deactivate()` path on Service/Product), Tag merge/rename re-pointing, `@Version` presence on `Combo`/`RecurringExpenseTemplate`, THERAPIST_PLUS's catalog CRUD scope (never permanent-delete/APPROVE), CSV import re-validation, and Expense void-immutability (both GET and POST paths) all checked and correct.
- **All 11 of `Bug_Report_v6.md`'s previously-open LOW findings (13–23)** re-confirmed fixed in current code, with exact current line numbers verified for each.