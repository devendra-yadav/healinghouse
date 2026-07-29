# Healing House Clinic — Expenses & Profit/Loss

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 26, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md` (adds a new Expenses module and a new report type) and `Security_RBAC_Requirements_v1.md` (adds a fifth role, `THERAPIST_PLUS`, and four new Access Matrix rows). Reuses the soft-delete catalog pattern from `Combos_Requirements_v1.md`/`Tags_Requirements_v1.md`, the `ProportionalAllocator`/report-scaffolding conventions from `Packages_Requirements_v1.md`/`Actual_Revenue_Reporting_Requirements_v1.md`, and reads (never writes) the existing `CommissionCalculator`.

---

## 1. Problem Statement

Every existing report (Daily, Period, Comparison, Patients, Performance, Actual Revenue) and the Dashboard are **revenue-only** — they show what the clinic billed and collected from patients, but nothing about what it spent to operate: raw materials (acupuncture needles, cupping sets, oils), equipment, cleaning products, decorative/consumable supplies, maintenance, rent, utilities, and staff pay. There is currently no way to answer "did we actually make a profit this month?"

This document defines **Expenses**: a way to record one-off and recurring costs against an admin-managed category list, and a new **Profit & Loss** report (Net Revenue − Total Expenses = Net Profit) for monthly/yearly review. It also introduces a fifth role, `THERAPIST_PLUS`, for a therapist who is trusted to record clinic purchases without being promoted to full Admin.

---

## 2. Goals

- Record a one-off expense: category, date, amount, vendor (free text), payment method, optional notes.
- Record recurring fixed/near-fixed costs (rent, salaries, utility bills) via a template that auto-generates the actual expense each period, so staff aren't relied on to remember them.
- Maintain an admin-manageable **Expense Category** list (e.g. Raw Materials, Equipment, Maintenance, Utilities, Rent, Salaries, Commission, Other) — completely separate from `Product`/`ClinicService`/`Tag`, which represent what the clinic *earns from*, not what it *spends on*.
- Record actual therapist salary/commission payouts as expenses, so Net Profit nets out the clinic's largest cost — using `CommissionCalculator`'s existing computed figure only as a read-only reference, never auto-posted.
- A new **Profit & Loss** report: Net Revenue, Total Expenses, Net Profit, by-category breakdown, month-over-month trend, filterable by date range, exportable to CSV/PDF like every other report.
- A "Total Expenses" / "Net Profit" KPI on the Dashboard, visible only to roles with report access.
- A new role, **`THERAPIST_PLUS`** — identical to `THERAPIST` in every other respect, additionally able to record/view expenses (but never P&L/revenue figures, and never expenses under a category flagged confidential — see §5.5).
- Every expense keeps an audit trail: who recorded it, and a soft-void (never a hard delete) if entered in error.

### Non-goals (explicitly out of scope for this iteration)

- **Receipt/invoice attachments (photos, PDFs)** — this app has no file-upload/storage capability anywhere today; adding one is a separate infrastructure decision, not bundled into v1.
- **A managed Vendor/Supplier entity** — vendor is a free-text field on each expense for v1. Can be promoted to its own master-data entity later if duplicate/typo'd vendor names become a real reporting problem.
- **Approval workflow** — an expense recorded by anyone with `EXPENSES`/`CREATE` is immediately final; no PENDING/APPROVED status, no approval step. Mirrors how appointments/payments already work — no approval workflow exists anywhere else in this app.
- **Budget limits / overspend alerts** — no per-category budget caps or notifications in this iteration.
- **Multi-currency / GST-tax breakdown** — amounts are plain ₹ `BigDecimal`, same as everywhere else in the app; no tax computation.
- **Weekly or custom-interval recurrence** — only `MONTHLY`, `QUARTERLY`, `YEARLY` (§3, §11).
- **Editing a voided expense** — once voided it is immutable; correcting a mistake means voiding it and recording a new, correct entry (§5.2).
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 New entity: `ExpenseCategory`

Admin-manageable master list, mirroring the soft-delete pattern already used by `Tag`/`Combo`/`ClinicService` — but a fully independent table, never linked to `Product`/`ClinicService`/`Tag`:

```java
@Entity
@Table(name = "expense_category", indexes = {
        @Index(name = "idx_expense_category_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** When true, every Expense under this category is invisible to THERAPIST_PLUS
     *  (§5.5) — filtered at the service layer, not a permission-matrix cell, since
     *  visibility here depends on the category the row belongs to, not just the role.
     *  Seeded true for "Salaries" and "Commission" (§5.4); an Owner/Admin can flag
     *  any other category confidential the same way via the category edit form. */
    @Builder.Default
    @Column(nullable = false)
    private boolean restrictedVisibility = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- No hard delete from the UI's normal path — `active` toggle only, exactly like `ClinicService`/`Product`/`Combo`. Permanent delete follows the same double-guard as those (§5.6).

### 3.2 New entity: `Expense`

```java
@Entity
@Table(name = "expense", indexes = {
        @Index(name = "idx_expense_date", columnList = "expenseDate"),
        @Index(name = "idx_expense_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_category_id", nullable = false)
    private ExpenseCategory category;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Free text for v1 — no managed Vendor entity (§2 non-goals). */
    private String vendorName;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;   // reuses the existing enum — no new payment concept

    /** Optional free-text label — e.g. the payee's name for a salary/commission payout (§5.4). */
    private String label;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private ExpenseStatus status = ExpenseStatus.ACTIVE;

    /** Set iff this row was auto-generated by the recurring-expense scheduler (§5.3);
     *  null for a manually entered one-off expense. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_expense_template_id")
    private RecurringExpenseTemplate sourceTemplate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_user_id", nullable = false)
    private User recordedBy;

    @Version
    private Long version;   // optimistic lock, mirrors Appointment/PatientWallet

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

```java
public enum ExpenseStatus {
    ACTIVE,   // counts toward every expense list/report
    VOIDED    // soft-deleted; excluded from reports by default, immutable (§5.2)
}
```

### 3.3 New entity: `RecurringExpenseTemplate`

```java
@Entity
@Table(name = "recurring_expense_template", indexes = {
        @Index(name = "idx_recurring_expense_template_active", columnList = "active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringExpenseTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_category_id", nullable = false)
    private ExpenseCategory category;

    @NotBlank
    @Column(nullable = false)
    private String label;   // e.g. "Monthly Rent", "Electricity Bill"

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal defaultAmount;

    private String vendorName;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurrenceFrequency frequency;

    @Column(nullable = false)
    private LocalDate startDate;

    /** Null = runs indefinitely. Once passed, the template auto-deactivates (§5.3). */
    private LocalDate endDate;

    /** Advanced by `frequency` after each generation (§5.3) — the scheduler's cursor. */
    @Column(nullable = false)
    private LocalDate nextDueDate;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;   // pause without deleting

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

```java
public enum RecurrenceFrequency {
    MONTHLY, QUARTERLY, YEARLY
}
```

### 3.4 `AppRole` — one new value

```java
public enum AppRole {
    OWNER, ADMIN, RECEPTIONIST, THERAPIST, THERAPIST_PLUS
}
```

- `THERAPIST_PLUS` is scoped identically to `THERAPIST` everywhere else in the app (own appointments/earnings only, via the same `User.therapist` link) — no existing THERAPIST-scoping code changes; a THERAPIST_PLUS user is simply a THERAPIST for every purpose except the additional grants below. The Access Matrix differences are: full CRUD (view/create/edit/deactivate, **not** permanent-delete) on Expenses-related modules **and** on the catalog modules Services/Products/Combos/Package Templates (§3.5, §5.5) — see the 2026-07-27 addendum in §5.5 for why the catalog grant was added on top of the original Expenses-only scope.
- A `THERAPIST_PLUS`-role `User` still requires `therapist` to be set (same validation `AppUserDetailsService`/`UserService` already enforce for `THERAPIST`).

### 3.5 `Module` — three new values

```java
public enum Module {
    DASHBOARD, PATIENTS, APPOINTMENTS, THERAPISTS,
    SERVICES, PRODUCTS, COMBOS, PACKAGE_TEMPLATES, PATIENT_PACKAGES,
    TAGS, WALLET,
    REPORTS_STANDARD, REPORTS_REVENUE, REPORTS_PROFIT_LOSS,
    EXPENSE_CATEGORIES, EXPENSES,
    USER_MANAGEMENT, ACCESS_MATRIX
}
```

- `EXPENSE_CATEGORIES` (master-data management) and `EXPENSES` (recording/viewing actual expense rows, including recurring templates) are kept as separate modules — same reasoning the existing `Module` javadoc already gives for `PACKAGE_TEMPLATES` vs. `PATIENT_PACKAGES`: they can carry different grants even though today's seed happens to restrict both to OWNER/ADMIN.
- `REPORTS_PROFIT_LOSS` is its own module, not folded into `REPORTS_REVENUE` — same precedent as `REPORTS_REVENUE` being split out from `REPORTS_STANDARD` for its extra financial sensitivity.

### 3.6 ER Diagram Update

```
EXPENSE_CATEGORY ||--o{ EXPENSE : "categorizes"
EXPENSE_CATEGORY ||--o{ RECURRING_EXPENSE_TEMPLATE : "categorizes"
RECURRING_EXPENSE_TEMPLATE ||--o{ EXPENSE : "auto-generates (nullable link)"
THERAPIST ||--o{ EXPENSE : "salary/commission payout (nullable)"
APP_USER ||--o{ EXPENSE : "recordedBy"
```

### 3.7 Rollout

`hibernate.ddl-auto: update` auto-creates `expense_category`, `expense`, `recurring_expense_template` — all new tables, no columns added to any existing entity, no backfill needed. `SecuritySeeder`'s existing `backfillFullAccessMatrix()` mechanism (already used for every prior module/role addition) automatically inserts `granted=false` placeholder rows for every new `(role, module, action)` triple on any already-deployed database, so the Access Matrix UI has a checkbox to render immediately — the explicit `grant(...)` calls for the intended defaults (§5.5 table) still need adding to `seedRolePermissions()` for fresh installs.

---

## 4. DTO Changes

### 4.1 New: `ExpenseCategoryForm`

```java
private Long id;
private String name;
private boolean active;
private boolean restrictedVisibility;
```

### 4.2 New: `ExpenseForm`

```java
private Long id;
private String label;           // optional — e.g. payee name for a salary/commission payout (§5.4)
private Long categoryId;
private LocalDate expenseDate;
private BigDecimal amount;
private String vendorName;
private PaymentMethod paymentMethod;
private String notes;
```

### 4.3 New: `RecurringExpenseTemplateForm`

```java
private Long id;
private Long categoryId;
private String label;
private BigDecimal defaultAmount;
private String vendorName;
private PaymentMethod paymentMethod;
private RecurrenceFrequency frequency;
private LocalDate startDate;
private LocalDate endDate;
private boolean active;
```

### 4.4 New: `ExpenseFilter`

```java
private LocalDate dateFrom;
private LocalDate dateTo;
private Long categoryId;
private String vendorName;
private PaymentMethod paymentMethod;
private ExpenseStatus status;    // default ACTIVE — "Show Voided" toggle sets it to include VOIDED
```

### 4.5 New: `ProfitLossReportDTO`

```java
private LocalDate dateFrom, dateTo;
private BigDecimal netRevenue;              // Σ grandTotal, COMPLETED appointments — same basis as Actual Revenue report
private BigDecimal totalExpenses;           // Σ amount, ACTIVE expenses in range
private BigDecimal netProfit;               // netRevenue - totalExpenses
private List<ExpenseCategoryBreakdownDTO> expensesByCategory;   // {categoryName, total}
private List<ProfitLossTrendPointDTO> trend;                    // {periodLabel, revenue, expenses, profit} — month buckets
```

### 4.6 New: `ExpenseListRowDTO`

```java
private Long id;
private LocalDate expenseDate;
private String label;
private String categoryName;
private BigDecimal amount;
private String vendorName;
private PaymentMethod paymentMethod;
private ExpenseStatus status;
private String recordedByUsername;
private boolean recurring;      // true iff sourceTemplate != null
```

---

## 5. Business Rules

### 5.1 Category isolation from the revenue catalog

- `ExpenseCategory` has no relationship to `Product`, `ClinicService`, or `Tag`. An expense for "acupuncture needles" is a cost row only — it is never selectable on an appointment, never billed to a patient, never touches commission. The two hierarchies (what the clinic earns from vs. what it spends on) are deliberately kept structurally separate so a future change to one can never accidentally affect the other.

### 5.2 Expense lifecycle — soft-void, not hard delete

- A `ACTIVE` expense is freely editable (category, date, amount, vendor, payment method, notes) by anyone with `EXPENSES`/`EDIT`.
- "Delete" (`EXPENSES`/`DELETE`) sets `status = VOIDED` — the row is never removed from the database. A voided expense is excluded from every report/total by default and becomes **immutable** — it cannot be edited or un-voided. Correcting a mistake means voiding the wrong entry and recording a fresh, correct one, preserving a clean audit trail (who voided what, and what replaced it, via `createdAt`/`recordedBy` on the new row).
- The Expense list page defaults to `ACTIVE`-only, with a "Show Voided" toggle (`ExpenseFilter.status`) — same UX convention as the existing "Show Inactive" toggle on Services/Products/Combos list pages.

### 5.3 Recurring expense generation

- A daily scheduled job (`RecurringExpenseScheduler`, `@Scheduled(cron = ...)`, proposed trigger 01:00 IST — §10, §11) scans every `active = true` `RecurringExpenseTemplate` where `nextDueDate <= today`.
- For each due template, it creates one `Expense` (`category`, `amount = defaultAmount`, `expenseDate = nextDueDate`, `vendorName`, `paymentMethod` copied from the template, `sourceTemplate` set, `recordedBy` = a system/service account or the template's original creator — flagged for confirmation, §11) and advances `nextDueDate` by one `frequency` interval (+1 month / +3 months / +1 year).
- If the newly advanced `nextDueDate` is past `endDate`, the template is auto-deactivated (`active = false`) after this final generation — mirrors the existing "auto-deactivate when left empty" pattern used for combos (core `CLAUDE.md`, Combos business rule).
- The generated `Expense` is a completely normal, editable `ACTIVE` row — if the actual bill (e.g. electricity) differs from the template's `defaultAmount`, staff simply edit that specific expense afterward. This is what makes variable-amount recurring costs (utilities) work the same way as fixed ones (rent, salary) without a separate "confirm" step blocking anything.
- Staff can also trigger "Generate Now" manually from the Recurring Templates page, for testing or to pull a due entry forward — same idempotency rule (`nextDueDate` only advances once per generation) applies regardless of trigger source.

### 5.4 Recording therapist salary/commission payouts

- Two seeded `ExpenseCategory` rows, **"Salaries"** and **"Commission"** (both `restrictedVisibility = true`, §5.5, §11), are the intended categories for actual payouts to therapists.
- There is no dedicated therapist link or category-level "payout" flag — a payout is recorded as an ordinary expense under one of these two categories, with the payee noted in the free-text `label` field (e.g. "Priya — July commission"). The actual payout amount is always manually entered and confirmed by staff, since real-world payouts can be partial, include advances, or be adjusted.
- Recording a payout this way does **not** modify `CommissionCalculator`'s own computation — that remains a pure read/report figure exactly as it is today. This is a separate, parallel record of the actual money paid out.

### 5.5 `THERAPIST_PLUS` role and category-level visibility

- `THERAPIST_PLUS` is granted `EXPENSES` module `VIEW/CREATE/EDIT/DELETE` (void), but is **never** granted `REPORTS_PROFIT_LOSS` or `REPORTS_REVENUE` — it cannot see Net Revenue, Net Profit, or any P&L summary, only the raw expense entries it and others record.
- Independently of the module/action grant, every expense query run on behalf of a `THERAPIST_PLUS` session **excludes** any `Expense` whose `category.restrictedVisibility = true` (i.e., "Salaries"/"Commission" by default, or any other category an Owner/Admin later flags confidential). This is implemented **inline in `ExpenseService`**, not the `PermissionAspect` — the same architectural choice already used for `THERAPIST`'s "own appointments only" scoping (`AppointmentController.enforceOwnAppointmentForTherapist`) and `ReportController.denyClinicWideReportsForTherapist` — because it depends on a data attribute (the category), not just the caller's role, which the aspect's coarse module/action gate can't express.
- `RECEPTIONIST` and plain `THERAPIST` get no grants on `EXPENSE_CATEGORIES`, `EXPENSES`, or `REPORTS_PROFIT_LOSS` at all — same "—" as their existing Wallet/Actual-Revenue rows.

**Addendum (2026-07-27, confirmed by clinic owner):** `THERAPIST_PLUS` is additionally granted full catalog management — `VIEW/CREATE/EDIT/DELETE` (**not** `APPROVE`/permanent-delete) — on `SERVICES`, `PRODUCTS`, `COMBOS`, and `PACKAGE_TEMPLATES`, widened from the original `VIEW`-only grant on those four modules. This is an intentional scope decision, not an oversight: a therapist trusted enough to be promoted to `THERAPIST_PLUS` for expense-recording is also trusted to add/edit/deactivate catalog items day-to-day, without needing a full Admin promotion. Permanent-delete stays OWNER/ADMIN-only, same as every other role. `SecuritySeeder.seedRolePermissions()`/`backfillTherapistPlusPermissions()` implement this; a static-review pass (`requirements/Bug_Report_v6.md` Finding 1) initially flagged the resulting seed change as an unreviewed escalation because this addendum hadn't been written yet — that finding has since been closed as not-a-bug now that the spec and code agree.

### 5.6 Permanent delete of an `ExpenseCategory`

- Only allowed once already deactivated (`active = false`) **and** unreferenced by any `Expense` or `RecurringExpenseTemplate` row (`existsByCategory`) — identical double-guard to `Combo`/`PackageTemplate`'s permanent delete (core `CLAUDE.md`, Soft delete / permanent delete business rule), gated by the `APPROVE` action.

### 5.7 Reporting basis — unaffected areas

- Commission calculation (`CommissionCalculator`) is entirely unaffected — it never reads `Expense`/`ExpenseCategory`, regardless of whether a payout was separately logged as an expense (§5.4).
- Stock decrement, double-booking conflict detection, discounts, wallet, and package consumption are all completely unrelated to this feature — Expenses touch no `Appointment`-side logic at all.

---

## 6. Service / Controller Changes

### 6.1 `ExpenseCategoryService` (new) / `ExpenseCategoryController` (new)

- `create/update/deactivate/activate/permanentlyDelete`, `findAllActive()`, `findAllIncludingInactive(Pageable)`, `search(query, pageable)` — same shape as `TagService`/`ComboService`.
- `GET /expense-categories`, `GET .../new`, `POST /expense-categories`, `GET .../{id}/edit`, `POST .../{id}`, `POST .../{id}/deactivate`, `POST .../{id}/activate`, `POST .../{id}/delete-permanent`.

### 6.2 `ExpenseService` (new) / `ExpenseController` (new)

- `create(ExpenseForm, recordedBy)`, `update(id, ExpenseForm)`, `void(id)`, `search(ExpenseFilter, Pageable)` → applies the `THERAPIST_PLUS` restricted-category filter (§5.5) when the caller's role is `THERAPIST_PLUS`.
- `GET /expenses`, `GET .../new`, `POST /expenses`, `GET .../{id}/edit`, `POST .../{id}`, `POST .../{id}/void`, plus CSV/PDF export of the raw list (`GET .../export-csv`, `.../export-pdf`).

### 6.3 `RecurringExpenseTemplateService` (new) / `RecurringExpenseTemplateController` (new)

- `create/update/pause(active=false)/resume(active=true)`, `generateNow(id)` (manual trigger, reuses the same generation logic as the scheduler), `findAllActive()`.
- `GET /expenses/recurring`, `GET .../new`, `POST /expenses/recurring`, `GET .../{id}/edit`, `POST .../{id}`, `POST .../{id}/pause`, `POST .../{id}/resume`, `POST .../{id}/generate-now`.

### 6.4 `RecurringExpenseScheduler` (new)

- One `@Scheduled` method, daily, iterating due templates via `RecurringExpenseTemplateService` (§5.3). Requires adding `@EnableScheduling` to `HealinghouseApplication` (not present today — §10).

### 6.5 `ProfitLossReportAggregator` (new) + `ReportController` extension

- `getProfitLossReport(dateFrom, dateTo)` → `ProfitLossReportDTO`. Reuses the existing date-range-defaulting (last 30 days) convention and the `Specification<Appointment>`/`COMPLETED`-only basis already established by `RevenueReportAggregator` for the revenue side; sums `Expense` where `status = ACTIVE` and `expenseDate` in range for the expense side.
- `GET /reports/profit-loss`, `GET .../export-csv`, `GET .../export-pdf` — same triad every other report follows, via `CsvExportUtil`/`PdfExportUtil`.

### 6.6 `DashboardService` extension

- Two new fields on `DashboardKpiDTO` (or a small sibling DTO): `totalExpensesThisMonth`, `netProfitThisMonth` — same computation basis as the P&L report, scoped to the current calendar month (matches every other Dashboard KPI's period).

---

## 7. UI / Template Changes

- `templates/expense-categories/list.html`, `.../form.html` — mirrors `templates/combos/list.html`/`.../form.html` (active/inactive toggle, pagination, permanent-delete modal), plus a "Restricted (hidden from Therapist+)" checkbox on the form.
- `templates/expenses/list.html`, `.../form.html` — filter bar (date range, category, vendor, payment method, Show Voided toggle), CSV/PDF export buttons, a Label column/field; the category-select on the form includes "Salaries" and "Commission" among the standard options — staff note payout/therapist details in the free-text Label field (§5.4), no special JS behavior needed.
- `templates/expenses/recurring-list.html`, `.../recurring-form.html` — template CRUD, pause/resume toggle, "Generate Now" button, next-due-date column.
- `templates/reports/profit-loss.html` — filter bar (date range), summary cards (Net Revenue, Total Expenses, Net Profit), by-category breakdown table/chart (Chart.js, consistent with existing report pages), month-over-month trend chart, CSV/PDF export buttons.
- `fragments/layout.html` — new "Expenses" nav item (gated by the `perm` bean, visible to OWNER/ADMIN/THERAPIST_PLUS) and a new "Profit & Loss" report nav item (gated to OWNER/ADMIN only).
- Dashboard (`templates/index.html` or equivalent) — two new KPI tiles, gated the same way existing KPI cards already are by permission.

---

## 8. Reporting / Dashboard Impact

Unlike every prior addendum (where this section is typically a non-goal), reporting **is** the core ask here:

- New **Profit & Loss** report (§6.5, §7) — Net Revenue (Σ `grandTotal`, `COMPLETED` appointments, same basis the Actual Revenue report already established) minus Total Expenses (Σ `ACTIVE` `Expense.amount` in range) = Net Profit, with a by-category breakdown and a month-over-month trend. Owner/Admin only.
- Two new Dashboard KPI tiles: Total Expenses (this month) and Net Profit (this month) — visible only to roles with `REPORTS_PROFIT_LOSS`/`VIEW`.
- The raw Expense list itself (§6.2, §7) is a separate, lower-sensitivity view available to `THERAPIST_PLUS` too (minus restricted categories) — it shows individual entries, not aggregated revenue/profit figures.
- No change to any existing report's numbers — Daily/Period/Comparison/Patients/Performance/Actual Revenue remain exactly as they are today; this is a purely additive report type.

---

## 9. Acceptance Criteria

1. Owner/Admin can create, edit, and deactivate/permanently-delete Expense Categories, gated the same way as Services/Products/Combos, with the same deactivated-and-unreferenced guard on permanent delete.
2. Owner/Admin (and `THERAPIST_PLUS`, minus restricted categories) can record a one-off expense with category, date, amount, vendor, payment method, and notes.
3. "Deleting" an expense sets it to `VOIDED`, hides it from default lists/reports, and makes it immutable — it cannot subsequently be edited.
4. A recurring expense template auto-generates an `ACTIVE` `Expense` on schedule, using its default amount; that generated expense is freely editable afterward if the real amount differs.
5. A recurring template's `nextDueDate` advances exactly once per generation (no duplicate generation on re-run) and the template auto-deactivates once past its `endDate`.
6. A therapist salary/commission payout can be recorded as an ordinary expense against the seeded "Salaries" or "Commission" category, with payee details noted in the Label field.
7. `THERAPIST_PLUS` retains every existing `THERAPIST` capability/scoping unchanged (own appointments, own earnings, etc.), can additionally record/view non-restricted expenses, but cannot view the Profit & Loss report, the Actual Revenue report, or any expense under a `restrictedVisibility` category (verified: a Salaries or Commission expense is invisible to a `THERAPIST_PLUS` user, visible to Owner/Admin).
8. `RECEPTIONIST` and plain `THERAPIST` have no access to Expense Categories, Expenses, Recurring Templates, or the Profit & Loss report.
9. The Profit & Loss report's Net Revenue figure reconciles with the existing Actual Revenue report's Net Revenue figure for the same date range (same underlying `COMPLETED`/`grandTotal` basis); Total Expenses reconciles with a manual sum of `ACTIVE` expenses in range; Net Profit = the difference.
10. Profit & Loss and the Expense list both support CSV and PDF export, consistent with every other report's export button placement and branded PDF letterhead/footer.
11. No regressions to existing appointment, commission, wallet, package, or combo behavior — Expenses touch none of that code.

---

## 10. Open Implementation Notes

- **First scheduled/background job in this codebase.** Every existing feature is purely request-driven; recurring expense generation needs `@EnableScheduling` (not currently present on `HealinghouseApplication`) and a decided trigger time (proposed: daily at 01:00 IST — §11). This is new infrastructure, not an extension of an existing pattern, and deserves its own careful review (idempotency on server restart, what happens if the app is down across a due date, etc.).
- **Category-level visibility filtering for `THERAPIST_PLUS` is a new *kind* of RBAC scoping.** Every existing THERAPIST scoping rule is ownership-based ("only appointments where I'm the therapist"). This one is attribute-based ("only expenses whose category isn't flagged confidential") — it needs its own small, explicit helper in `ExpenseService`, not a reuse of `PermissionAspect` or the existing `enforceOwnXxxForTherapist` helpers, since those check identity, not a joined entity's flag.
- **Adding a 5th `AppRole` is a wider change than it looks.** Beyond `RolePermission` seeding, every place `AppRole` values are enumerated or switched on must be audited: `SecurityConfig`, `AppUserDetailsService`, `UserAdminController`'s role dropdown/validation, and any hardcoded `if (role == AppRole.THERAPIST)` check outside the aspect (e.g. the "must have therapist FK set iff THERAPIST" validation, which now must also cover `THERAPIST_PLUS`). Missing one of these is the most likely source of a subtle bug (e.g. `THERAPIST_PLUS` silently falling through a switch's default case).

---

## 11. Decided (Open Questions Resolved)

All confirmed by the clinic owner during brainstorming, July 26, 2026:

- **Expense Category:** a new, independent, admin-manageable master list (own table, own soft-delete lifecycle) — not `Tag`, not linked to `Product`/`ClinicService` (§3.1, §5.1).
- **Vendor/Supplier:** free-text field on each expense for v1, no separate managed entity (§3.2, §2 non-goals).
- **Recurring expenses:** supported via `RecurringExpenseTemplate`; auto-generates an actual, immediately-final `Expense` using the template's default amount each period; staff edit afterward if the real bill differs (§5.3).
- **Attachments/receipts:** out of scope for v1 (§2 non-goals) — this app has no file-upload capability today.
- **Approval workflow:** none — direct entry by anyone with `EXPENSES`/`CREATE` is immediately final (§2 non-goals, §5.2).
- **Edit/delete:** editable while `ACTIVE`; delete is a soft-void, immutable once voided (§5.2).
- **New role:** `THERAPIST_PLUS` — identical to `THERAPIST` everywhere else, plus expense record/view access (§3.4, §5.5).
- **Expense visibility scope for `THERAPIST_PLUS`:** can record/view non-restricted expense entries only; no access to P&L or revenue reports (§5.5).
- **Therapist payouts:** real salary/commission payouts are recorded as ordinary Expenses (categories "Salaries" and "Commission," both seeded `restrictedVisibility = true`), with payee details noted in the free-text `label` field rather than a dedicated therapist field (§5.4).
- **Salary/payout visibility:** OWNER and ADMIN can see these entries; `THERAPIST_PLUS` cannot (§5.5, §12 Access Matrix).
- **Scope of this document:** requirements only — implementation is a separate follow-up task once this document is reviewed.

### Flagged for explicit confirmation before implementation (reasonable defaults assumed, not directly discussed)

- **Recurrence granularity limited to `MONTHLY`/`QUARTERLY`/`YEARLY`** — no weekly or arbitrary custom-interval recurrence (assumed sufficient for rent/salary/utility-style costs; flag if a weekly cost — e.g. a recurring cleaning service — is actually needed).
- **Recurring job trigger time: daily at 01:00 IST** (assumed reasonable off-peak time; flag if a different time is preferred).
- **Starter category seed set:** `SecuritySeeder` seeds Raw Materials, Equipment, Maintenance, Utilities, Rent, Salaries (`restrictedVisibility = true`), Commission (`restrictedVisibility = true`), Other — as a convenience starting point, all editable/deactivatable afterward.
- **`recordedBy` on an auto-generated recurring expense:** assumed to be the user who created the `RecurringExpenseTemplate` (flag if a dedicated "system" user or the template's last editor is preferred instead).
- **THERAPIST_PLUS conversion:** assumed an Owner/Admin can change an existing `THERAPIST`-role user to `THERAPIST_PLUS` (and back) via the existing `/admin/users` edit screen's role dropdown, exactly like switching between any other two roles today (flag if a different provisioning flow is wanted).

---

*Document Version 1.0 — Healing House Clinic — July 2026*
