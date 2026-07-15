# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Healing House Clinic Management System** — a Spring Boot + Thymeleaf + MySQL web application for managing clinic operations, appointments, patients, therapists, services, and products. Designed as an internal admin tool (no authentication in current phase).

## Commands

```bash
# Run the application (dev mode with DevTools hot-reload, default profile)
./mvnw spring-boot:run          # Linux/Mac
mvnw.cmd spring-boot:run        # Windows

# Run against a specific profile (test / preprod / prod)
./mvnw spring-boot:run -Dspring-boot.run.profiles=preprod

# Build (produces jar + a zip distribution in target/, via maven-assembly-plugin)
./mvnw package

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Run a single test method
./mvnw test -Dtest=ClassName#methodName
```

Application runs at `http://localhost:8080` (default profile).

### Deployment (Linux)

`src/main/linux/` holds ops scripts packaged into the assembly zip:
- `bin/start_healinghouse_app.bash <env>` — `env` must be `test`, `preprod`, or `prod`; requires `HEALING_HOUSE_DB_PASSWORD` env var; launches with `--spring.profiles.active=<env>` and a dedicated `logback-spring.xml`
- `bin/stop_healinghouse_app.bash` — stops the running jar
- `conf/logback-spring.xml` — logging config used in deployed environments

## Database Setup

- **Engine:** MySQL 8+
- **Timezone:** Asia/Kolkata
- `ddl-auto: update` — Hibernate auto-creates/alters tables on startup; no migration files yet
- `DataSeeder` (implements `CommandLineRunner`) seeds master data on first run **only if tables are empty**

Per-profile config (`src/main/resources/application*.yml`):

| Profile | Database | User | Password | Port |
|---------|----------|------|----------|------|
| default (dev) | `healing_house_clinic` | `clinic_user` | `StrongPass123!` (hardcoded) | 8080 |
| `test` | `healing_house_clinic_test` | `hh_user_test` | `${HEALING_HOUSE_DB_PASSWORD}` | 8080 |
| `preprod` | `healing_house_clinic_preprod` | `hh_user_preprod` | `${HEALING_HOUSE_DB_PASSWORD}` | 9824 |
| `prod` | `healing_house_clinic` | `hh_user` | `${HEALING_HOUSE_DB_PASSWORD}` | 9825 |

## Architecture

**Package root:** `com.clinic.healinghouse`

```
entity/       JPA entities + enums
repository/   Spring Data JPA repositories
service/      Business logic (transactions live here)
controller/   Spring MVC — return Thymeleaf view names
dto/          Form-binding + report DTOs (e.g. AppointmentForm, DailyReportDTO, TherapistEarningsDTO)
config/       DataSeeder
exception/    @ControllerAdvice global error handling
util/         Date/currency formatters, CsvExportUtil, PdfExportUtil (opencsv / iText)
```

**Note on naming:** the `/services` URL space (ClinicService CRUD) is served by `TreatmentController` / `TreatmentService`, not a `ServiceController` — avoids clashing with the Spring `service` package/term.

**Templates:** `src/main/resources/templates/`
- `fragments/layout.html` — shared header/nav/footer; all pages extend this
- Sub-folders mirror domain: `patients/`, `therapists/`, `services/`, `products/`, `appointments/`, `tags/`, `reports/`
- All pages are mobile-responsive (see `requirements/Responsive_UI_Requirements_v1.md` / `RESPONSIVE_TESTING_GUIDE.md`)

**Frontend:** Bootstrap 5.3 (CDN) + Vanilla JS + Chart.js (CDN) — no npm/node build step.

## Domain Model

Core entities and their relationships:

```
Patient ──< Appointment >── Therapist
                │
          ┌─────┴──────┐
          │             │
   AppointmentServiceLine   AppointmentProductLine
          │             │
      ClinicService   Product
          │             │
          └──────┬──────┘
                 │
              Tag (many-to-many, shared by Service & Product)

Patient ──1:1── PatientWallet ──< WalletTransaction >── Appointment (nullable link)

Combo ──< ComboServiceItem >── ClinicService
      ──< ComboProductItem >── Product

Appointment ──< AppointmentCombo >── Combo
AppointmentServiceLine >── AppointmentCombo (nullable — null for a standalone line)
AppointmentProductLine >── AppointmentCombo (nullable — null for a standalone line)
```

**Key design decisions:**
- Line items (`AppointmentServiceLine`, `AppointmentProductLine`) snapshot price at time of booking — catalog changes don't affect historical records
- Each line item also carries its **own `therapist`** (defaults to the appointment's main therapist but can be reassigned per line) — this is what per-line commission/earnings reporting is built on. See `requirements/Per_Line_Therapist_Assignment_Requirements_v1.md`.
- `Tag` is a free-text, admin-managed label, many-to-many with both `ClinicService` and `Product`. It **replaces the old hardcoded `category` field.** `TagService` supports rename/merge/delete/autocomplete (`GET /tags/search`). See `requirements/Tags_Requirements_v1.md`.
- `Appointment.getBalanceDue()` is a `@Transient` computed field (grandTotal − amountPaid)
- Per-appointment discount (`discountType`: NONE/PERCENTAGE/FLAT, `discountValue` as typed, `discountAmount` as resolved ₹) is distributed proportionally across every service+product line into a nullable `discountedLineTotal` — null means no discount, so behavior is unchanged. Each line's `getEffectiveLineTotal()` transient getter falls back to the raw `priceAtTime`/`lineTotal` when null; templates and any future consumer should read through that getter, not the raw fields, to show/use the actual charged amount. See `AppointmentService.applyDiscount`/`distributeDiscount`.
- All monetary values use `BigDecimal` (Indian Rupees ₹)
- `@EqualsAndHashCode(exclude = {"serviceLines","productLines","combos"})` on `Appointment` avoids circular Lombok issues
- Lazy fetching on all `@ManyToOne` associations
- Patient detail page shows full appointment history via `PatientHistoryService` (see `requirements/Patient_History_Requirements_v1.md`)
- Therapist detail page (`GET /therapists/{id}`) shows profile + period earnings (via `CommissionCalculator.calculateEarnings`, defaulting to current calendar month) + filterable appointment history; history includes appointments where the therapist is the main therapist **or** only handled a reassigned line item, via `AppointmentSpec.hasTherapistId` (see `requirements/Therapist_Details_Requirements_v1.md`)
- `Appointment.durationMinutes` (default 60, `DEFAULT 60` at the DB level so existing rows backfill automatically) drives `getEndDateTime()` (`@Transient`, = `appointmentDateTime + durationMinutes`) — used by both conflict detection and the therapist calendar. See `requirements/Therapist_Calendar_Requirements_v1.md`.
- `Combo` (catalog) has **no stored price** — `ComboService.computeOriginalPrice`/`computeComboPrice` always compute live from current `ClinicService`/`Product` prices, the same snapshot-at-use-time philosophy line items already follow. `AppointmentCombo` is the per-appointment snapshot ("combo X applied here with this discount"), decoupled from the live `Combo` row the same way `priceAtTime` is decoupled from `ClinicService.price`. See the Combos business rule below.
- `Therapist.owner` is a real persisted `boolean` column (Lombok accessor `isOwner()`), not inferred — replaced an earlier version that treated `commissionRate`/`fixedMonthlySalary` both being zero/null as "is the owner," which silently misclassified any new therapist saved before her payout terms were configured. `config/OwnerFlagBackfill.java` (always-on `CommandLineRunner`, not gated by `DataSeeder`'s dev/test `@Profile`) does a one-time idempotent fix-up for databases seeded before this column existed, flagging the therapist named "Marcia Gomes Yadav". `therapists/form.html` has an explicit "This therapist is the owner" checkbox.
- `Appointment` has `@Version` optimistic locking (mirroring `PatientWallet`) — guards against lost-update races on double-clicked/double-submitted cancel, no-show, and edit requests that would otherwise double-apply a wallet reversal/debit. `AppointmentService.saveWithConflictCheck` uses `saveAndFlush` and translates a version conflict into a friendly `IllegalStateException`.

## Business Rules

**Commission calculation — owned by `CommissionCalculator`, consumed by `ReportService` / `DashboardService`:**
```java
BigDecimal commission = (servicesRevenue.add(productsRevenue)).multiply(therapist.getCommissionRate());
BigDecimal bonus = servicesCount >= therapist.getPerformanceBonusThreshold()
    ? therapist.getPerformanceBonusAmount() : BigDecimal.ZERO;
BigDecimal totalVariablePay = commission.add(bonus);
```
Revenue/count inputs are attributed **per line-item therapist**, not just the appointment's main therapist.

- `servicesRevenue`/`productsRevenue` (the commission base) only include lines whose `ClinicService`/`Product` carries a **`Commission`** tag (case-insensitive); untagged lines don't count towards commission. See `CommissionCalculator.COMMISSION_TAG`.
- `servicesCount` (the bonus-threshold input) only counts service lines tagged **`Bonus`** (case-insensitive). See `CommissionCalculator.BONUS_TAG`.
- Both filters are implemented as `JOIN ... tags t WHERE LOWER(t.name) = LOWER(:tagName)` in `AppointmentServiceLineRepository` / `AppointmentProductLineRepository` — services/products must be tagged accordingly in the Tags UI or they silently contribute ₹0 to commission/bonus reports.

**`TherapistEarningsDTO`** carries both tag-filtered and untagged figures per therapist — the untagged ones are reporting-only and never feed commission/bonus math:
- `servicesRevenue`/`productsRevenue` (Commission-tagged) and `servicesCount` (Bonus-tagged) — the payout inputs above
- `allServicesRevenue`/`allProductsRevenue`/`allServicesCount` — totals regardless of tag
- `bonusTaggedServicesRevenue` — revenue of Bonus-tagged service lines only (not used in payout, informational)
- Daily/period report tables and their CSV/PDF exports show all of these as: `Services Rev.(All)`, `Products Rev.(All)`, `Services(All)`, `Services Rev.(Bonus tagged)`, `Products Rev.(Commission tagged)`, `Services(Bonus tagged)`. The comparison report only shows the tag-filtered figures.

- **Marcia Gomes Yadav** (owner) — `commissionRate = 0`, `fixedMonthlySalary = 0`; `CommissionCalculator` zeroes out only her commission/bonus/`totalVariablePay` — the `allServicesRevenue`/`allProductsRevenue`/`allServicesCount`/`bonusTaggedServicesRevenue` reporting figures are still computed for her (e.g. therapist detail page "Revenue (All)" card)

**Discounts** — deliberately isolated from commission/reporting:
- A discount is entered once per appointment (`PERCENTAGE` or `FLAT`, staff's choice) and resolved server-side in `AppointmentService.applyDiscount`, capped so it never exceeds the pre-discount subtotal (and `PERCENTAGE` can't exceed 100%).
- `distributeDiscount` splits the resolved ₹ amount across every line proportional to its share of the subtotal; lines are processed smallest-raw-total first and the last (largest) line absorbs whatever rounding remainder is left, so per-line shares always sum exactly to the discount amount.
- Only `Appointment.grandTotal` (and everything derived from it — `amountPaid`, `getBalanceDue()`, `getPaymentStatus()`, `DashboardService`'s revenue KPIs/trend) reflects the discount, since that's what the patient actually owes/paid. `priceAtTime`, `lineTotal`, `totalServiceAmount`/`totalProductAmount`, and every commission/bonus/report query stay on the original undiscounted figures — a discount never reduces a therapist's commission.
- Editing an appointment to clear the discount (`discountType = NONE`) resets every line's `discountedLineTotal` back to `null` and restores `grandTotal` to the raw subtotal; discount is only editable while the appointment is still `SCHEDULED`, same rule as line items.
- Stock is decremented only when an appointment is marked `COMPLETED`
- Status flow: `SCHEDULED` → `COMPLETED` | `CANCELLED` | `NO_SHOW`
- Payment methods: `CASH`, `UPI`, `BANK_TRANSFER`, `CARD`, `OTHER`
- Amount paid can never exceed `grandTotal`: validated both client-side (`appointments/form.html`'s `checkPaymentExceedsTotal`, live on input + blocking on submit) and server-side (`AppointmentService.createAppointment`/`updateAppointment`, checked once `grandTotal` is finalized post-discount, throws `IllegalArgumentException`). In edit mode `amountPaid` is `prepaidBase + newPaymentAmount` (prepaid is the existing total or a corrected value via the pencil-edit), so the check sums both against the current `grandTotal`.

**Combos** — bundles of services/products sold at a discounted combo price, layered underneath the whole-appointment discount:
- Admin-managed catalog (`/combos`): `Combo` + its `ComboServiceItem`/`ComboProductItem` (item + quantity, no shared supertype so two separate item tables mirror `AppointmentServiceLine`/`AppointmentProductLine`'s split). No tags, no hard delete — `active` flag only, like `ClinicService`/`Product`.
- Selecting a combo on the appointment form (`fragments/combo-picker-modal.html`, backed by `GET /combos/search` and `GET /combos/{id}/detail`) expands it into ordinary `AppointmentServiceLine`/`AppointmentProductLine` rows tagged with a shared client-side `comboGroupKey`. Server-side, `AppointmentService.buildComboSelections` re-resolves each combo's `discountType`/`discountValue` from the **live** `Combo` catalog entry — never trusts a client-sent discount — and creates one `AppointmentCombo` per selection.
- **Two-phase discounting** (the mechanics change to `AppointmentService`, replacing what used to be a single discount pass):
  1. `applyComboDiscount` resolves each combo's own discount against just that combo's lines' raw `lineTotal`, distributing it via the shared `distributeAmount` allocator (same sort-ascending/last-line-absorbs-remainder algorithm the original single-phase `distributeDiscount` always used).
  2. `applyDiscount`/`distributeDiscount` (the pre-existing whole-appointment discount) now resolve against `computeEffectiveSubtotal()` — the sum of every line's *current* `getEffectiveLineTotal()` (post-combo) — so a manual discount layers on top of whatever combo discount a line already carries, rather than re-discounting from scratch. For a zero-combo appointment this collapses back to the original single-phase formula exactly, since every line's effective total then equals its raw total.
- Combo groups are locked as a unit on the appointment form: staff can edit a combo line's therapist (commission attribution only) but not its service/product/quantity, and can only remove the whole group via "Remove Combo," never a single line within it. This falls out of `updateAppointment`'s existing full clear-and-rebuild of `serviceLines`/`productLines` every save — `combos` is now cleared and rebuilt the same way, so omitting a combo's `comboSelections` entry on the next submit is all "removal" requires.
- Commission, stock decrement, and double-booking conflict detection are all unaffected — combo lines are ordinary `AppointmentServiceLine`/`AppointmentProductLine` rows; commission queries and `AppointmentSpec.hasTherapistId` key off `priceAtTime`/`lineTotal`/`therapist`, never `appointmentCombo` or `discountedLineTotal`.
- `appointments/detail.html`'s combo-group sections filter lines via `Appointment.getServiceLinesForCombo(ac)`/`getProductLinesForCombo(ac)` (plain Java methods, not an inline Thymeleaf expression) — a SpringEL selection like `${appointment.serviceLines.?[appointmentCombo == ac]}` throws `EL1008E: Property or field 'ac' cannot be found` at runtime, because `.?[...]`'s predicate evaluates with the candidate list element as its root, making the outer `th:each` variable invisible inside it. Same reasoning behind `getStandaloneServiceLines()`/`getStandaloneProductLines()` and `getStandaloneServiceTotal()`/`getStandaloneProductTotal()` — the Services/Products table footers must total only the standalone lines shown in that table, not `totalServiceAmount`/`totalProductAmount` (which include combo lines, rendered in their own section above both tables).
- See `requirements/Combos_Requirements_v1.md` for the full spec.

**Prepaid Balance (Wallet)** — a payment source alongside cash/UPI/card, deliberately isolated from discounts and revenue recognition:
- `PatientWallet` (1:1 `Patient`, `@Version` optimistic lock, lazily created on first use — `WalletService.getOrCreateWallet`) holds a `balance` that must never go negative; every debit path (`WalletService.applyToAppointment`/`refund`) validates against the current balance before decrementing, throwing `IllegalArgumentException` on insufficiency. `WalletService.getBalance` never creates a wallet row, so merely viewing a patient's page has no side effect.
- Every change is recorded in an immutable `WalletTransaction` ledger: `TOP_UP`/`REFUND` (real money moves — carries a `PaymentMethod` for cash reconciliation) or `USAGE`/`REVERSAL` (internal transfer — carries an `Appointment` link instead, no money physically moves).
- `Appointment.walletAmountApplied` folds into `amountPaid` (mirrors `discountAmount`'s role as a resolved, persisted tracking value) — `getBalanceDue()` needs no wallet-specific logic since it already nets out `amountPaid`.
- Applying wallet balance is a **target, not a delta**: `AppointmentForm.walletAmountApplied` represents the total wallet-sourced amount the appointment should carry (pre-populated from the persisted value on edit), and `AppointmentService.createAppointment`/`updateAppointment` compute `delta = requested − existing.walletAmountApplied`, issuing a `USAGE` (delta > 0) or `REVERSAL` (delta < 0) via `WalletService`. The ceiling `walletRequested.min(grandTotal)` is silently capped, never rejected (same style as `applyDiscount`'s `resolved.min(subtotal)`) — this single formula is what drives **automatic reversal** whenever an appointment's owed amount drops below its previously-applied wallet amount: discount added/increased, a line removed, the appointment cancelled/marked no-show (`AppointmentService.reverseFullWalletIfAny`, called from `cancelAppointment`/`markAsNoShow`), or staff manually reducing the applied amount on edit.
- The target-not-delta model is a backend/DTO contract only — `appointments/form.html`'s edit-mode UI hides it from staff to prevent them mistaking the field for incremental input (a real incident: a 2nd wallet payment typed into what looked like an empty box actually overwrote the target and reversed the 1st payment). The visible edit-mode box is `walletAdditionalInput`, an additive "Additional from Wallet Now" amount defaulting to 0; JS (`computeWalletApplied()`) adds it to `EXISTING_WALLET_APPLIED` and writes the result into the hidden `walletAppliedInput` (`name=walletAmountApplied`, the field actually submitted) on every keystroke and again right before submit. A pencil icon (`toggleWalletAppliedEdit()`) lets staff retype the target directly for a one-off correction, mirroring the pre-existing "Amount Prepaid" pencil-edit (`prepaidDisplay`/`prepaidCorrectionInput`/`togglePrepaidEdit()`) — the two pencils are mutually exclusive (editing one disables the other's input). Create mode still uses a single direct-entry field since there's no prior amount to add on top of.
- Two independent client-side checks guard the wallet box (`checkWalletIssues()` in `appointments/form.html`), sharing one alert area: (1) insufficient wallet balance — compares the *incremental* amount (`walletApplied − EXISTING_WALLET_APPLIED`), not the full target, against `currentWalletBalance`, since that balance already excludes whatever this appointment previously drew; (2) total-exceeds-grand — fires if the wallet portion alone (plus any pre-existing cash) already covers/exceeds `grandTotal`. `checkPaymentExceedsTotal()` (the "Payment Received Now" alert) only runs once `checkWalletIssues()` has cleared, so an overpayment warning always surfaces under whichever field actually caused it instead of always blaming the cash box.
- Revenue recognition is unaffected by this feature: `DashboardService`/reports still read off `Appointment.grandTotal` regardless of payment source — a wallet-funded appointment counts as revenue at completion exactly like a cash-funded one, while a top-up/refund is never revenue (it's a liability changing hands, not earned income).
- Naming: all code is `Wallet*` throughout (`PatientWallet`, `WalletService`, `WalletTransaction`, `WalletController`), never `Prepaid*` — that avoids colliding with the pre-existing, unrelated `AppointmentForm.prepaidCorrection`/`AppointmentService`'s local `prepaidBase` variable, which mean "amount already paid on this appointment before this edit." UI copy still says "Prepaid Balance," matching how the feature is described to staff.
- See `requirements/Prepaid_Balance_Requirements_v1.md` for the full spec.
- `fragments/wallet-modals.html`'s `topUpModal` fragment's `<form id="topUpModalForm">` renders its `action` from whatever `patientId` is known **at page load** — on `appointments/form.html`'s New Appointment page that's `null` until a patient is picked, so `appointments/form.html` must keep the `action` in sync client-side via `updateTopUpFormAction()` (called on patient-select change and on initial load); a hidden `patientId` input alone is not enough since the controller reads it as a `@PathVariable`, not a request param. Also watch fragment-argument expressions like `${editMode} ? appointment.patient.id : null` — every branch needs its own `${}` (`${editMode} ? ${appointment.patient.id} : null`), otherwise Thymeleaf treats the unwrapped branch as a literal string instead of evaluating it.

**Double-booking conflicts — warn, never hard-block:**
- `AppointmentService.findConflicts` checks the requested `[appointmentDateTime, appointmentDateTime + durationMinutes)` window against every other appointment where the *same therapist* is either the main therapist **or** assigned to any service/product line (same "busy" definition as `AppointmentSpec.hasTherapistId`). This runs for every therapist involved — main + all line overrides — not just the main therapist.
- Only `SCHEDULED`/`COMPLETED` appointments count; `CANCELLED`/`NO_SHOW` never conflict. Back-to-back slots (one's end == another's start) don't conflict either.
- On save/update, if conflicts are found and the `forceSave` checkbox wasn't checked, `AppointmentController` re-renders `appointments/form.html` with a warning banner (listing each conflicting therapist/patient/time) instead of persisting — all entered data is preserved on the re-render. Checking "Save anyway" bypasses the check for that submission only.
- When editing, the appointment's own id is excluded so it never conflicts with its own (unmoved) slot.
- Per-line therapist reassignment (`POST /appointments/{id}/service-lines/{lineId}/reassign-therapist` and the product-line equivalent) is subject to the same check, via the shared `AppointmentService.findConflictsForTherapist(therapistId, start, end, excludeAppointmentId)` (the single-therapist building block `findConflicts` loops over for every therapist on the form). A conflicting reassignment isn't applied — it flashes the conflict back to `appointments/detail.html`'s warning banner with a "Reassign Anyway" button (`forceReassign=true` on resubmit), mirroring the form's "Save anyway" gate.
- `AppointmentService.MAX_DURATION_MINUTES` caps any appointment at 24h (`validateDuration`, enforced in `createAppointment`/`updateAppointment`/`rescheduleAppointment`) — this is what makes `findConflicts`'s ±1-day DB pre-filter window provably safe: a candidate can't start far enough in the past to still reach into the requested window while sitting outside that margin.
- `getPaymentStatus()`: a `null` `grandTotal` is `"N/A"` (genuinely unpriced); a `grandTotal` of exactly `0` (e.g. a 100%-discounted appointment) is `"PAID"` — nothing owed, nothing to pay, distinct from "never priced."

**Soft delete / permanent delete — `ClinicService`, `Product`, `Combo` (all three follow the same pattern):**
- Deactivate (`active = false`) is the default "delete" — never touches historical appointment line items, which are fully decoupled via price/therapist snapshotting. Deactivating a service/product also strips it out of any `Combo` that bundles it (`ComboService.handleServiceDeactivated`/`handleProductDeactivated` — a combo's price is always live-computed, so removal is free); a combo left with zero items is auto-deactivated, since a combo can never have none (`ComboService.CatalogItemRemovalResult` carries a count back for the flash message).
- Reactivate (`POST .../{id}/activate`) flips `active` back to `true` — the only UI path back, exposed via a "Show Inactive" toggle on each list page (`services/list.html`, `products/list.html`, `combos/list.html`) that switches the query to `findAllIncludingInactive` (paginated) instead of the active-only list.
- Permanent delete (`POST .../{id}/delete-permanent`) is only allowed once already deactivated, **and** only if unreferenced: `TreatmentService.permanentlyDelete`/`ProductService.permanentlyDelete` reject if any `AppointmentServiceLine`/`AppointmentProductLine` or any `Combo` still references the item; `ComboService.permanentlyDelete` rejects if any `AppointmentCombo` references it. Rejections throw `IllegalArgumentException` with a specific reason, shown via flash message. UI: a second, separate confirmation modal (`deletePermanentModal`) next to the existing deactivate modal, only rendered for already-inactive rows.
- List-page search (`TreatmentService`/`ProductService`/`ComboService`'s paginated `search(query, ...)`) matches active **and** inactive rows — lets staff find a deactivated item by name/tag without paging through the whole inactive list; this is distinct from the unpaginated `search(query)` used by booking-flow/combo-picker autocompletes, which stays active-only (and, for combos, further filtered by `ComboService.isSelectable` — every bundled service/product must still be active).
- `PaginationUtil.clampPage(requested)` (floors negative page numbers to 0, paired with the pre-existing `clampPageSize`) is applied on every paginated list endpoint (`AppointmentController`, `PatientController`, `TherapistController`, `TreatmentController`, `ProductController`, `ComboController`) — a stray/manually-edited negative `page` query param used to crash the page.

**Global error handling — `exception/GlobalExceptionHandler.java` (`@ControllerAdvice`):**
- Last-resort safety net for stale "Edit"/"Delete" links on removed master data (`EntityNotFoundException`) and any other uncaught exception, which otherwise surfaced as Spring's Whitelabel stack-trace page. Normal page controllers get a flash-messaged redirect (falls back to the request's `Referer` header, else `/`); a genuinely unexpected exception renders `templates/error.html`.
- Branches to a small `{"error": "..."}` JSON body instead for any `@ResponseBody` endpoint (the typeahead/autocomplete search endpoints on `PatientController`/`ComboController`/`TagController`) — a redirect or HTML response is meaningless to a `fetch()` caller and previously broke it with a silent JSON-parse failure. Note: this project is on **Jackson 3.x**, so the import is `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson...`.

## Phased Implementation (current state)

The `requirements/Healing_House_Clinic_Requirements_v1.md` file is the authoritative spec (v1.2). Phases:

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Project bootstrap, layout, HomeController | Done |
| 1 | Master data CRUD (patients, therapists, services, products, tags) + DataSeeder | Done |
| 2 | Appointment management + line items | Done |
| 3 | Dashboard + reports + analytics + therapist earnings/commission | Done |
| 4 | Polish, UX, testing, documentation, performance | **Next up** |

**Phase 3 (done):** `DashboardService` populates the KPI cards, today's appointments, low-stock alerts, 7-day revenue trend, and 30-day tag revenue breakdown on `/`. `ReportService` (backed by `ReportAggregator` + `CommissionCalculator`) drives six report pages under `/reports`: `daily`, `period`, `comparison` (multi-therapist), `patients` (acquisition), `performance` (product/service), `revenue` (actual billed/collected — see below). Every report has CSV and PDF export endpoints (`/{report}/export-csv`, `/{report}/export-pdf`) via `CsvExportUtil` (opencsv) and `PdfExportUtil` (iText 7).

**Actual Revenue report (`GET /reports/revenue`, `RevenueReportAggregator`)** — added because the five original reports' "Revenue" figures (`totalServiceAmount`/`totalProductAmount`, `priceAtTime`/`lineTotal`) are pre-discount, pre-combo, billed-regardless-of-collection numbers — correct as the commission base (discounts never reduce commission, per the Discounts rule above) but not what the clinic actually earned. Those five pages now label every such figure "(Pre-Discount)" with a tooltip explaining why, with no change to the underlying numbers. The new report reads only existing fields (no schema change): **Net Revenue** = Σ`grandTotal` (billed after all discounts), **Collected** = Σ`amountPaid`, **Outstanding** = Σ`getBalanceDue()`, plus Gross Revenue, Combo Discounts Given, Manual Discounts Given, and Wallet-Funded Portion — filterable by date range, status, therapist, patient, service/product/tag, and payment method. By default, and regardless of which statuses the filter includes, the summary cards/totals only ever count `COMPLETED` appointments (matching the wallet rule "revenue at completion"); other statuses are visible only in the drill-down table, never summed into the headline figures. See `requirements/Actual_Revenue_Reporting_Requirements_v1.md`.

**`PdfExportUtil`** — branded letterhead (logo + title) and a footer (rule line + "Confidential" text + "Page N of {total}") on every report PDF. The footer is stamped via a `PdfDocumentEvent.END_PAGE` handler (`FooterEventHandler`, registered per-document in `newDocument`), **not** a loop over `pdfDoc.getPage(1..N)` after `document.add()` finishes — iText's `Document` auto-flushes each page's dictionary once a later page starts (a memory-saving measure), so a post-hoc loop finds earlier pages already flushed and `page.getPageSize()` throws `NullPointerException: this.map is null`. This breaks on *any* report spanning 2+ pages, which is easy to miss testing with small local data (see the Period Report PDF export bug, in production only, fixed 2026-07-11). The "of {total}" page count isn't known until layout finishes, so it's drawn into a shared `PdfFormXObject` placeholder that every page's footer references but only `FooterEventHandler.writeTotalPageCount` (called from `finish`, right before `document.close()`) actually fills in — safe because the XObject is its own indirect object, unaffected by its host page already being flushed. `regularFont()`/`boldFont()` are `ThreadLocal` (a `PdfFont` binds to whichever `PdfDocument` first flushes it, so each `generate*Pdf` call needs its own font instances) — always pair `initFontsForDocument()` (called from `newDocument`) with the `.remove()` cleanup in `finish`.

**Since Phase 3, also shipped (not tracked as numbered phases but documented under `requirements/`):**
- Tags feature — replaced the old category field (`Tags_Requirements_v1.md`)
- Per-line therapist assignment on service/product lines (`Per_Line_Therapist_Assignment_Requirements_v1.md`)
- Patient appointment history view (`Patient_History_Requirements_v1.md`)
- Therapist detail/history view (`Therapist_Details_Requirements_v1.md`)
- Mobile-responsive UI pass across all pages (`Responsive_UI_Requirements_v1.md`)
- Deployment tooling: `test`/`preprod`/`prod` Spring profiles, Linux start/stop scripts, logback config, zip assembly build
- Live patient name/phone autocomplete — `GET /patients/search?q=` (`PatientController`) returns JSON `PatientSuggestionDTO` (id, fullName, phone) via `PatientService.search`/`PatientRepository.searchActive`, capped at 8 results; backs the debounced typeahead on the patients list search box and the appointments list patient filter (`patients/list.html`, `appointments/list.html`). Appointment filtering matches patient name **or** phone via `AppointmentSpec.patientNameOrPhoneContains`.
- Per-appointment discounts (percentage or flat ₹, proportionally distributed across line items) — see the Discounts business rule above and `AppointmentService.applyDiscount`/`distributeDiscount`. Shown in `appointments/form.html` (live preview), `detail.html` (per-line strikethrough + discount badge), and `list.html` (discount badge).
- Appointment duration + double-booking conflict warning — see the Double-booking business rule above (`requirements/Therapist_Calendar_Requirements_v1.md`, Phase A).
- Therapist calendar (`GET /therapists/{id}/calendar`, `therapists/calendar.html`) — read-only day/week/month schedule per therapist via FullCalendar v6 (CDN, no build step), fed by `GET /appointments/calendar-feed` (JSON, `AppointmentService.findCalendarEvents`). Below 576px it switches to a `listWeek` agenda view with the view-switcher moved to a footer toolbar, since the 7-column time grid doesn't fit a phone screen. Clicking an event opens `appointments/{id}`; clicking an empty slot opens `appointments/new` pre-filled via `therapistId`/`appointmentDateTime` query params (`requirements/Therapist_Calendar_Requirements_v1.md`, Phase B).
- PDF report redesign — branded letterhead + footer (page numbers, confidentiality notice) via `PdfExportUtil`; see the `PdfExportUtil` note above for the `END_PAGE` event handler pattern and why multi-page reports need it.
- Amount-paid-exceeds-total guard on the appointment form — see the payment-methods business rule above.
- Patient prepaid balance / wallet — top-up, apply-to-appointment (with automatic reversal whenever an appointment's owed amount shrinks below what's applied), and refund, backed by an auditable `WalletTransaction` ledger; see the Prepaid Balance (Wallet) business rule above and `requirements/Prepaid_Balance_Requirements_v1.md`. UI: `patients/detail.html` (balance card + paginated history), `appointments/form.html` (Apply from Wallet input + live balance/insufficiency checks), `appointments/detail.html` (Paid from Wallet row), all sharing a reusable top-up/refund modal (`fragments/wallet-modals.html`).
- Appointment detail page: "View Profile" link on the Patient card (`appointments/detail.html`) always navigates to `/patients/{id}`, regardless of how the appointment was reached — distinct from the `returnUrl`-gated "Back to Patient" button, which only appears when navigated from a patient's page and preserves that origin/filter context.
- Combos — admin-managed service/product bundles at a discounted price, selectable on the appointment form and layered under the whole-appointment discount via a two-phase discount engine; see the Combos business rule above and `requirements/Combos_Requirements_v1.md`. New: `Combo`/`ComboServiceItem`/`ComboProductItem`/`AppointmentCombo` entities, `ComboService`/`ComboController` (`/combos` CRUD + search + detail), `templates/combos/`, `fragments/combo-picker-modal.html`. `appointments/form.html` gained a "Choose Combo" picker with locked, grouped line rows; `appointments/detail.html` shows a "Combo Packages" section (above the standalone Services/Products tables, whose totals now exclude combo lines) with a per-combo savings badge. The combo picker's per-line rate/running-total preview is threaded from the live catalog price returned by `GET /combos/{id}/detail` (`ComboDetailDTO.ComboDetailItemDTO.price`), not a page-load-time snapshot, so it can't silently diverge from what the server actually charges if a price changes while the form is open.
- Actual Revenue report — see above and `requirements/Actual_Revenue_Reporting_Requirements_v1.md`.
- Permanent delete (in addition to the pre-existing deactivate/reactivate) for `ClinicService`/`Product`/`Combo`, and pagination + "Show Inactive"/search on all three list pages — see the Soft delete / permanent delete business rule above.
- App-wide `TimeZone.setDefault(Asia/Kolkata)` set explicitly in `HealinghouseApplication.main` (belt-and-suspenders alongside the DB-level timezone setting) so date/time handling is consistent regardless of host JVM default.
- Optimistic locking (`@Version`) on `Appointment` + a global `@ControllerAdvice` (`GlobalExceptionHandler`) + `templates/error.html` — see the relevant business rules above. Full findings/fixes trail in `requirements/Bug_Report_v1.md` and `requirements/Bug_Report_v2.md` (34/34 tests passing after v2's fixes).

When implementing a specific step, reference it as "Phase X Step X.Y" from the requirements doc. `requirements/PHASE3_IMPLEMENTATION_GUIDE.md` has the detailed Phase 3 implementation notes if similar step-by-step guidance is needed for Phase 4.

## Key Patterns

- Controllers use `RedirectAttributes` for flash messages after form submissions
- Service layer owns `@Transactional` boundaries; controllers stay thin
- Enum fields persisted as `EnumType.STRING`
- Lombok: `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on entities; `@RequiredArgsConstructor` on services/controllers
- `@CreationTimestamp` / `@UpdateTimestamp` handle audit fields automatically
- Report/export endpoints follow `GET /reports/{report}` (HTML) + `GET /reports/{report}/export-csv` + `GET /reports/{report}/export-pdf`, all sharing the same `ReportService` query and date-range defaulting (last 30 days) logic
- Appointment form's "Amount Paid" field auto-clears its `0`/`0.00` default on focus (`clearZeroOnFocus`/`restoreZeroIfEmpty` in `appointments/form.html`) so staff can type straight in; a real pre-paid amount is left untouched for manual editing
- Button color convention for row/card actions: `btn-sm btn-outline-secondary` for "View" links, `btn-sm btn-outline-primary` for "Edit" links — consistent across `patients/list.html`, `therapists/list.html`, `appointments/list.html`, and detail-page card headers (e.g. the "View Profile" link on `appointments/detail.html`'s Patient card).


## VERY Impotant and MUST DO
- Be very concise in your responses.
- Dont need to be polite or humble. Give 'to the point' answer


## Rules for models:
You are building a production-grade Spring Boot web application.

- Main reasoning and important decisions → Always use Sonnet 5
- Sub-agents:
  → Use cheap/fast model for: boilerplate, simple classes, utilities, refactoring, formatting, running commands (/run), tests that are straightforward.
  → Use Sonnet 5 for: architecture, security, complex business logic, database design, performance-critical parts, or anything I explicitly say is important.

When in doubt, ask me first before spawning a sub-agent with cheap model on important parts.