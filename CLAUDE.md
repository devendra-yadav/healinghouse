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
```

**Key design decisions:**
- Line items (`AppointmentServiceLine`, `AppointmentProductLine`) snapshot price at time of booking — catalog changes don't affect historical records
- Each line item also carries its **own `therapist`** (defaults to the appointment's main therapist but can be reassigned per line) — this is what per-line commission/earnings reporting is built on. See `requirements/Per_Line_Therapist_Assignment_Requirements_v1.md`.
- `Tag` is a free-text, admin-managed label, many-to-many with both `ClinicService` and `Product`. It **replaces the old hardcoded `category` field.** `TagService` supports rename/merge/delete/autocomplete (`GET /tags/search`). See `requirements/Tags_Requirements_v1.md`.
- `Appointment.getBalanceDue()` is a `@Transient` computed field (grandTotal − amountPaid)
- Per-appointment discount (`discountType`: NONE/PERCENTAGE/FLAT, `discountValue` as typed, `discountAmount` as resolved ₹) is distributed proportionally across every service+product line into a nullable `discountedLineTotal` — null means no discount, so behavior is unchanged. Each line's `getEffectiveLineTotal()` transient getter falls back to the raw `priceAtTime`/`lineTotal` when null; templates and any future consumer should read through that getter, not the raw fields, to show/use the actual charged amount. See `AppointmentService.applyDiscount`/`distributeDiscount`.
- All monetary values use `BigDecimal` (Indian Rupees ₹)
- `@EqualsAndHashCode(exclude = {"serviceLines","productLines"})` on `Appointment` avoids circular Lombok issues
- Lazy fetching on all `@ManyToOne` associations
- Patient detail page shows full appointment history via `PatientHistoryService` (see `requirements/Patient_History_Requirements_v1.md`)
- Therapist detail page (`GET /therapists/{id}`) shows profile + period earnings (via `CommissionCalculator.calculateEarnings`, defaulting to current calendar month) + filterable appointment history; history includes appointments where the therapist is the main therapist **or** only handled a reassigned line item, via `AppointmentSpec.hasTherapistId` (see `requirements/Therapist_Details_Requirements_v1.md`)
- `Appointment.durationMinutes` (default 60, `DEFAULT 60` at the DB level so existing rows backfill automatically) drives `getEndDateTime()` (`@Transient`, = `appointmentDateTime + durationMinutes`) — used by both conflict detection and the therapist calendar. See `requirements/Therapist_Calendar_Requirements_v1.md`.

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

**Double-booking conflicts — warn, never hard-block:**
- `AppointmentService.findConflicts` checks the requested `[appointmentDateTime, appointmentDateTime + durationMinutes)` window against every other appointment where the *same therapist* is either the main therapist **or** assigned to any service/product line (same "busy" definition as `AppointmentSpec.hasTherapistId`). This runs for every therapist involved — main + all line overrides — not just the main therapist.
- Only `SCHEDULED`/`COMPLETED` appointments count; `CANCELLED`/`NO_SHOW` never conflict. Back-to-back slots (one's end == another's start) don't conflict either.
- On save/update, if conflicts are found and the `forceSave` checkbox wasn't checked, `AppointmentController` re-renders `appointments/form.html` with a warning banner (listing each conflicting therapist/patient/time) instead of persisting — all entered data is preserved on the re-render. Checking "Save anyway" bypasses the check for that submission only.
- When editing, the appointment's own id is excluded so it never conflicts with its own (unmoved) slot.

## Phased Implementation (current state)

The `requirements/Healing_House_Clinic_Requirements_v1.md` file is the authoritative spec (v1.2). Phases:

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Project bootstrap, layout, HomeController | Done |
| 1 | Master data CRUD (patients, therapists, services, products, tags) + DataSeeder | Done |
| 2 | Appointment management + line items | Done |
| 3 | Dashboard + reports + analytics + therapist earnings/commission | Done |
| 4 | Polish, UX, testing, documentation, performance | **Next up** |

**Phase 3 (done):** `DashboardService` populates the KPI cards, today's appointments, low-stock alerts, 7-day revenue trend, and 30-day tag revenue breakdown on `/`. `ReportService` (backed by `ReportAggregator` + `CommissionCalculator`) drives five report pages under `/reports`: `daily`, `period`, `comparison` (multi-therapist), `patients` (acquisition), `performance` (product/service). Every report has CSV and PDF export endpoints (`/{report}/export-csv`, `/{report}/export-pdf`) via `CsvExportUtil` (opencsv) and `PdfExportUtil` (iText 7).

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

When implementing a specific step, reference it as "Phase X Step X.Y" from the requirements doc. `requirements/PHASE3_IMPLEMENTATION_GUIDE.md` has the detailed Phase 3 implementation notes if similar step-by-step guidance is needed for Phase 4.

## Key Patterns

- Controllers use `RedirectAttributes` for flash messages after form submissions
- Service layer owns `@Transactional` boundaries; controllers stay thin
- Enum fields persisted as `EnumType.STRING`
- Lombok: `@Data @Builder @NoArgsConstructor @AllArgsConstructor` on entities; `@RequiredArgsConstructor` on services/controllers
- `@CreationTimestamp` / `@UpdateTimestamp` handle audit fields automatically
- Report/export endpoints follow `GET /reports/{report}` (HTML) + `GET /reports/{report}/export-csv` + `GET /reports/{report}/export-pdf`, all sharing the same `ReportService` query and date-range defaulting (last 30 days) logic
- Appointment form's "Amount Paid" field auto-clears its `0`/`0.00` default on focus (`clearZeroOnFocus`/`restoreZeroIfEmpty` in `appointments/form.html`) so staff can type straight in; a real pre-paid amount is left untouched for manual editing


## VERY Impotant and MUST DO
- Be very concise in your responses.
- Dont need to be polite or humble. Give 'to the point' answer