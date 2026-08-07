# Healing House Clinic — Employment Contracts

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** August 7, 2026
**Status:** Draft — open questions resolved, some defaults flagged for confirmation before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md` (adds a new Contracts module hanging off `Therapist`) and `Security_RBAC_Requirements_v1.md` (adds a new `CONTRACTS` Access Matrix row, OWNER-exclusive on every mutating action). Reuses the `@Version` optimistic-lock convention (`Appointment`/`PatientWallet`/`Expense`), the branded-PDF letterhead/footer convention (`PdfExportUtil`), and the "own record only" THERAPIST scoping pattern (`AppointmentController.enforceOwnAppointmentForTherapist`, `TherapistController.enforceOwnTherapist`).

---

## 1. Problem Statement

Therapist employment terms (joining date, position, salary, probation, commission %, performance bonus, notice period) are currently negotiated and recorded outside the system — nothing in the app captures a formal, signed employment contract per therapist. This document defines a **Contracts** feature: the Owner generates a professional PDF employment contract for a therapist from existing profile data plus a small set of contract-specific inputs, reviews/edits the content freely before finalizing, and once approved, it becomes the therapist's official, immutable contract record — viewable by that therapist, renewable, and cancellable.

---

## 2. Goals

- A "Generate Contract" action on the Therapist detail page, Owner-only, pre-filled with the therapist's existing profile data (name, phone, email, address, specialization, plus current commission/salary/bonus settings if already set).
- A contract-terms form capturing: joining date, contract period, position, probation (with conditional probation-period/probation-salary fields), monthly salary, commission %, monthly performance bonus (threshold + amount), notice period.
- Any term left blank is simply omitted from the generated contract text — no "N/A" placeholders, no empty clauses.
- Generated contract is shown on-screen for **free-form review and editing** (add/edit/delete any text anywhere) before it's finalized.
- A two-stage lifecycle: **Draft** (freely editable, re-editable any number of times, no PDF yet) → **Approved** (Owner-only final action; locks the content, renders the branded PDF with clinic logo + Owner signature + clinic stamp, and becomes the therapist's official contract).
- Approving a contract syncs its commission %/salary/bonus terms into the therapist's live payroll fields (`Therapist.commissionRate`/`fixedMonthlySalary`/`performanceBonusThreshold`/`performanceBonusAmount`) so `CommissionCalculator` always reflects the latest signed terms.
- Cancel, Renew, and Delete actions on a contract — all Owner-only.
- Therapist can view (and download) their own contract's PDF — read-only, no edit/approve/cancel/renew/delete access.
- One active (`APPROVED`) contract per therapist at a time; renewing a contract automatically supersedes the previous one, with full history retained and viewable.

### Non-goals (explicitly out of scope for this iteration)

- **No employee signature on the PDF.** See §5.7 — an in-app "Acknowledge" action replaces a wet/digital signature for v1.
- **No approval workflow beyond Owner.** ADMIN has zero access to this module, not even view (§5.1) — consistent with how sensitive this document class is.
- **No contract template library / multiple contract "types."** One structured template, populated by the fields in §2, with optional clauses omitted when their backing field is blank.
- **No e-mail/SMS delivery of the contract to the therapist.** They see it in-app on their next login; no notification integration exists in this app today.
- **No multi-clinic / multi-branch letterhead variation** — one clinic identity (Healing House Clinic & Academy, Pondicherry), same as every other branded PDF in this app.
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 New entity: `EmploymentContract`

```java
@Entity
@Table(name = "employment_contract", indexes = {
        @Index(name = "idx_employment_contract_therapist", columnList = "therapist_id"),
        @Index(name = "idx_employment_contract_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmploymentContract {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "therapist_id", nullable = false)
    private Therapist therapist;

    /** e.g. "HHC-2026-0007" — assigned once, at draft creation (§5.6). */
    @Column(nullable = false, unique = true)
    private String contractNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private ContractStatus status = ContractStatus.DRAFT;

    // ---- Contract terms (form inputs, §4.1) ----
    @Column(nullable = false)
    private LocalDate joiningDate;

    /** Null = open-ended/ongoing engagement (§5.3). */
    private Integer contractPeriodMonths;

    @NotBlank
    @Column(nullable = false)
    private String position;

    @Builder.Default
    @Column(nullable = false)
    private boolean probationApplicable = false;

    /** Required iff probationApplicable. 1–6. */
    private Integer probationPeriodMonths;

    /** Required iff probationApplicable. */
    @Column(precision = 10, scale = 2)
    private BigDecimal probationSalary;

    /** The standing monthly salary — post-probation figure if probation applies, otherwise the
     *  only salary figure. Mandatory (§5.3 assumption, flagged §11). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlySalary;

    /** Percentage as entered (e.g. 10.00 = 10%), not the [0,1] fraction Therapist.commissionRate
     *  stores — converted on sync (§5.4). Null = no commission clause in the contract. */
    @Column(precision = 5, scale = 2)
    private BigDecimal commissionPercent;

    /** Minimum sessions/month to earn the bonus. Null = no bonus clause. */
    private Integer performanceBonusThreshold;

    /** Bonus amount paid in a month where the threshold is met. Null = no bonus clause. */
    @Column(precision = 10, scale = 2)
    private BigDecimal performanceBonusAmount;

    @Column(nullable = false)
    private Integer noticePeriodMonths;

    // ---- Generated / editable content ----
    /** The free-form, owner-editable contract body (rich text / HTML). Populated on draft
     *  generation from the template (§9), then freely edited (§5.2). Locked once APPROVED. */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contractBodyHtml;

    /** Rendered only at approve-time (§5.5); null while DRAFT. */
    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] pdfContent;

    // ---- Lifecycle metadata ----
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private User approvedBy;

    private LocalDateTime cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledBy;

    @Column(length = 500)
    private String cancellationReason;

    /** In-app acknowledgment by the therapist (§5.7) — never on the PDF itself. */
    private LocalDateTime acknowledgedAt;

    /** Set when this contract was generated via "Renew" from an earlier one (§5.6). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_contract_id")
    private EmploymentContract previousContract;

    @Version
    private Long version;   // optimistic lock, mirrors Appointment/PatientWallet/Expense

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

```java
public enum ContractStatus {
    DRAFT,       // freely editable, no PDF yet, not the therapist's official contract
    APPROVED,    // locked content, PDF rendered, the therapist's one active contract
    CANCELLED,   // was APPROVED, terminated early by the Owner — terminal
    SUPERSEDED   // was APPROVED, replaced by a renewal — terminal
}
```

### 3.2 `Module` — one new value

```java
public enum Module {
    DASHBOARD, PATIENTS, APPOINTMENTS, THERAPISTS,
    SERVICES, PRODUCTS, COMBOS, PACKAGE_TEMPLATES, PATIENT_PACKAGES,
    TAGS, WALLET,
    REPORTS_STANDARD, REPORTS_REVENUE, REPORTS_PROFIT_LOSS,
    EXPENSE_CATEGORIES, EXPENSES,
    USER_MANAGEMENT, ACCESS_MATRIX, AUDIT_LOG,
    CONTRACTS
}
```

No new `AppRole` or `PermissionAction` values needed — `CONTRACTS` uses the existing `VIEW/CREATE/EDIT/DELETE/APPROVE` actions (§5.1).

### 3.3 ER Diagram Update

```
THERAPIST ||--o{ EMPLOYMENT_CONTRACT : "has (history)"
EMPLOYMENT_CONTRACT }o--|| EMPLOYMENT_CONTRACT : "previousContract (nullable, renewal chain)"
APP_USER ||--o{ EMPLOYMENT_CONTRACT : "createdBy / approvedBy / cancelledBy"
```

### 3.4 Rollout

`hibernate.ddl-auto: update` auto-creates `employment_contract` — a new table, no columns added to any existing entity. `SecuritySeeder`'s existing `backfillFullAccessMatrix()` mechanism inserts `granted=false` placeholder rows for every new `(role, module, action)` triple on already-deployed databases; the intended defaults (§5.1) still need explicit `grant(...)` calls added to `seedRolePermissions()` for fresh installs.

---

## 4. DTO Changes

### 4.1 New: `EmploymentContractForm` (the term-entry form, §2)

```java
private Long therapistId;
private LocalDate joiningDate;
private Integer contractPeriodMonths;      // optional
private String position;
private boolean probationApplicable;
private Integer probationPeriodMonths;     // required iff probationApplicable, 1-6
private BigDecimal probationSalary;        // required iff probationApplicable
private BigDecimal monthlySalary;
private BigDecimal commissionPercent;      // optional
private Integer performanceBonusThreshold; // optional, required together with performanceBonusAmount
private BigDecimal performanceBonusAmount; // optional, required together with performanceBonusThreshold
private Integer noticePeriodMonths;
```

Pre-population on `GET /therapists/{id}/contracts/new` (fresh, non-renewal): `monthlySalary` ← `Therapist.fixedMonthlySalary`, `commissionPercent` ← `Therapist.commissionRate × 100`, `performanceBonusThreshold`/`performanceBonusAmount` ← same-named `Therapist` fields, if set. On a **renewal**, the form is instead pre-populated from the previous contract's own terms (§5.6), not the therapist's raw profile.

### 4.2 New: `ContractContentUpdateDTO` (the free-form review/edit save, §5.2)

```java
private Long id;
private String contractBodyHtml;
```

### 4.3 New: `ContractListRowDTO`

```java
private Long id;
private String contractNumber;
private ContractStatus status;
private String position;
private LocalDate joiningDate;
private LocalDateTime approvedAt;
private LocalDateTime acknowledgedAt;   // null = not yet acknowledged
```

### 4.4 New: `ContractCancelForm`

```java
private Long id;
private String cancellationReason;   // optional free text
```

---

## 5. Business Rules

### 5.1 Access control — Owner-exclusive, therapist view-own-only

- `CONTRACTS` module: **OWNER** granted `VIEW/CREATE/EDIT/DELETE/APPROVE` — every action. **ADMIN gets no grant at all**, not even `VIEW` — confirmed explicitly; this is a deliberately narrower module than every other OWNER/ADMIN-shared one (Services, Products, Combos, etc.). **RECEPTIONIST** gets no grant. **THERAPIST**/**THERAPIST_PLUS** granted `VIEW` only.
- `VIEW` for a THERAPIST/THERAPIST_PLUS caller is additionally scoped in-controller to **their own linked therapist's contracts only** (`PermissionService.currentTherapistId()` must equal `contract.therapist.id`), mirroring `TherapistController.enforceOwnTherapist` — a direct URL to another therapist's contract throws `AccessDeniedException` via the existing `GlobalExceptionHandler` flash-redirect path.
- `EDIT` (used for saving free-form draft edits, §5.2) and `APPROVE` (used for both Approve and Cancel, §5.5/§5.6 — same reuse pattern `WALLET`/`PATIENT_PACKAGES` already use for their refund action) and `DELETE`/`CREATE` are all OWNER-only; no role-scoping nuance needed beyond the module gate since only OWNER holds them.

### 5.2 Draft lifecycle — freely re-editable, no PDF yet

- `POST /therapists/{id}/contracts` (`ContractService.generateDraft`, `CONTRACTS`/`CREATE`) builds a new `DRAFT` row: assigns `contractNumber` (§5.6), copies the therapist snapshot + submitted `EmploymentContractForm` fields, and renders `contractBodyHtml` from the template (§9) — omitting every clause whose backing field is blank/false (§5.3).
- The review screen (`templates/contracts/review.html`) loads `contractBodyHtml` into a rich-text editor. "Save Draft" (`POST /contracts/{id}/content`, `CONTRACTS`/`EDIT`) persists free-form edits — callable any number of times while `status = DRAFT`. No PDF exists at this point; nothing is yet visible to the therapist.
- If a `DRAFT` already exists for a therapist when "Generate Contract" is clicked again, the existing draft is reopened for continued editing rather than creating a second, orphaned draft — one in-progress draft per therapist at a time.
- Only `DRAFT`-status content (`contractBodyHtml` and every term field) is ever mutable. `APPROVED`/`CANCELLED`/`SUPERSEDED` are permanently locked.

### 5.3 Optional-clause omission

- Every term left blank on the form is simply absent from the rendered contract — no placeholder text, no empty section headers. Concretely: `contractPeriodMonths` blank → the contract states an open-ended engagement (phrasing varies per §9) rather than a fixed term; `probationApplicable = false` → no probation clause at all (and `probationPeriodMonths`/`probationSalary` stay null, no such inputs shown on the form, §7); `commissionPercent` blank → no commission clause; `performanceBonusThreshold`/`performanceBonusAmount` blank → no bonus clause.
- `performanceBonusThreshold` and `performanceBonusAmount` are required **together** — the form/service rejects one being set without the other, since a threshold with no payout (or a payout with no threshold) is meaningless.
- `monthlySalary` and `noticePeriodMonths` are always shown in the contract — see §11 for why these are treated as mandatory inputs even though the original ask didn't explicitly flag them so.

### 5.4 Approve — locks content, renders PDF, syncs payroll

- `POST /contracts/{id}/approve` (`CONTRACTS`/`APPROVE`, OWNER-only, only valid from `DRAFT`):
  1. Locks `contractBodyHtml` and every term field (no further edits possible).
  2. Renders the final branded PDF (§9, §10) from `contractBodyHtml` wrapped in the clinic letterhead (logo, clinic name/address) with an Owner signature + clinic stamp block at the foot — stored into `pdfContent`.
  3. Sets `status = APPROVED`, `approvedAt = now`, `approvedBy = current user`.
  4. **Syncs payroll fields onto `Therapist`** in the same transaction: `commissionRate = commissionPercent / 100` (validated against the existing `[0,1]` `@DecimalMax` bound on `Therapist.commissionRate` — a contract with e.g. 150% commission is rejected at approve-time, before it ever reaches that column), `fixedMonthlySalary = monthlySalary`, `performanceBonusThreshold`/`performanceBonusAmount` copied straight across. A blank `commissionPercent`/bonus pair leaves the corresponding `Therapist` field(s) untouched (never force-nulled) — approving a contract with no commission clause doesn't erase a commission rate set some other way.
  5. If `previousContract` is set (this is a renewal, §5.6), that previous contract's `status` flips to `SUPERSEDED` in the same transaction.
- If any step fails (validation, optimistic-lock conflict on `Therapist`), the whole approval rolls back — no partial state where a PDF exists but payroll wasn't synced, or vice versa.

### 5.5 Cancel

- `POST /contracts/{id}/cancel` (`CONTRACTS`/`APPROVE`, OWNER-only, only valid from `APPROVED`) — sets `status = CANCELLED`, `cancelledAt`/`cancelledBy`, optional `cancellationReason` free text (e.g. "Therapist resigned," "Terminated for cause"). Terminal — no further transitions.
- Cancelling does **not** automatically modify `Therapist.commissionRate`/`fixedMonthlySalary`/`performanceBonus*` — those stay at their last-synced values until the Owner either edits the therapist directly or approves a new contract. Flagged for confirmation (§11) — the alternative (auto-zeroing payroll fields on cancel) risks surprising the Owner if she cancels-and-immediately-renews.
- Cancelling a contract does **not** touch the linked therapist's `active` flag — that remains a fully separate action on the Therapist record itself.

### 5.6 Renew

- `POST /therapists/{id}/contracts/renew` (`CONTRACTS`/`CREATE`, OWNER-only, only valid when the therapist has an `APPROVED` contract) — opens the same draft-generation flow as a fresh contract, but pre-fills `EmploymentContractForm` from the **current contract's own terms** (not the therapist's raw profile defaults) and sets the new `DRAFT` row's `previousContract` to the current contract's id.
- Goes through the identical `DRAFT` → review/edit → `APPROVE` flow (§5.2, §5.4). Only on **approving** the new contract does the old one flip to `SUPERSEDED` (§5.4 step 5) — an abandoned/deleted renewal draft never affects the still-`APPROVED` original.
- **One active contract per therapist**, enforced two ways: (a) UI — the Therapist detail page shows "Generate Contract" only when no `APPROVED` contract exists, "Renew Contract" otherwise; (b) service-layer guard — `ContractService.generateDraft` (non-renewal path) rejects if the therapist already has an `APPROVED` contract, forcing the Renew path instead.
- Contract number assignment: `HHC-{year}-{sequence}`, sequence = count of contracts created in that calendar year + 1 (e.g. `HHC-2026-0007`) — flagged assumption, §11.

### 5.7 Acknowledgment — replaces a wet/digital employee signature

- The PDF itself carries **only** the Owner's signature + clinic stamp (§9) — no employee signature line.
- Once `APPROVED`, the therapist's own "My Contract" view shows the PDF plus an **"Acknowledge"** button (visible only if `acknowledgedAt` is still null). `POST /contracts/{id}/acknowledge` is self-service — any authenticated user may acknowledge **only their own linked therapist's own contract** (no `CONTRACTS` permission needed beyond the existing `VIEW` grant, mirroring `AccountController`'s self-service, ungated-by-`@RequiresPermission` pattern for password change) — sets `acknowledgedAt = now`.
- This is a records-only proof of receipt, visible to the Owner as a badge/timestamp on the contract detail page (e.g. "Acknowledged by Priya S. on 10 Aug 2026, 14:32") — never rendered onto the PDF, never blocks any other action.

### 5.8 Delete

- `POST /contracts/{id}/delete` (`CONTRACTS`/`DELETE`, OWNER-only) — **only valid while `status = DRAFT`.** A `DRAFT` has no legal weight and nothing else references it, so this is a genuine hard delete, not a soft one.
- `APPROVED`/`CANCELLED`/`SUPERSEDED` contracts can never be deleted — they're the therapist's employment history and, once `APPROVED`, carry a signed PDF; this mirrors the "immutable once real" pattern already used by `Expense` (voided, not deleted) and permanent-delete's referenced-row guard elsewhere in this app.

---

## 6. Service / Controller Changes

### 6.1 `ContractService` (new)

- `generateDraft(therapistId, EmploymentContractForm, currentUser)` → new/reopened `DRAFT` `EmploymentContract` (§5.2, §5.6).
- `renewDraft(currentContractId, EmploymentContractForm, currentUser)` → new `DRAFT` with `previousContract` set (§5.6).
- `updateContent(id, ContractContentUpdateDTO)` → persists edited `contractBodyHtml`, `DRAFT` only (§5.2).
- `approve(id, currentUser)` → locks content, renders PDF, syncs `Therapist` payroll fields, supersedes `previousContract` if any (§5.4).
- `cancel(id, ContractCancelForm, currentUser)` (§5.5).
- `delete(id)` — `DRAFT` only (§5.8).
- `acknowledge(id, currentUser)` — self-service, own-therapist-only (§5.7).
- `findHistoryForTherapist(therapistId)` → all contracts for a therapist, newest first, for the Therapist detail page's history list.
- `getPdf(id)` → `pdfContent` bytes, `APPROVED`-only (a `DRAFT` has none yet).

### 6.2 `ContractController` (new)

```
GET  /therapists/{therapistId}/contracts/new         — new-contract form (§4.1, §7)
POST /therapists/{therapistId}/contracts              — generateDraft, redirect to review
POST /therapists/{therapistId}/contracts/renew        — renewDraft, redirect to review
GET  /contracts/{id}/review                            — draft review/edit screen (§7)
POST /contracts/{id}/content                           — save free-form edits (DRAFT only)
POST /contracts/{id}/approve
POST /contracts/{id}/cancel
POST /contracts/{id}/delete                            — DRAFT only
POST /contracts/{id}/acknowledge                       — self-service, own contract only
GET  /contracts/{id}                                   — detail page (metadata, actions, PDF link)
GET  /contracts/{id}/pdf                                — inline PDF stream, APPROVED only
```

Every route except `/acknowledge` is gated `@RequiresPermission(module = CONTRACTS, action = ...)`; `/acknowledge` and the `VIEW`-gated routes additionally run the own-therapist scoping check for a THERAPIST/THERAPIST_PLUS caller (§5.1).

### 6.3 `ContractTemplateRenderer` (new, or a method on `ContractService`)

- Pure function: `(Therapist, EmploymentContractForm) → String (HTML)`. Builds the initial `contractBodyHtml` from the template in §9, substituting merge fields and omitting optional clauses per §5.3. Thymeleaf can render this server-side the same way any other template renders, just producing a text fragment instead of a full page.

### 6.4 `ContractPdfExportUtil` (new, or an addition to `PdfExportUtil`)

- Converts the **current** `contractBodyHtml` (post owner-editing) into the final PDF, wrapped in the clinic letterhead and a signature/stamp footer block (§9, §10). This is a materially different code path from every existing `PdfExportUtil.generate*Pdf` method, which build structured tables/DTOs directly via iText's layout API rather than converting arbitrary rich-text HTML — see §10 for the technical approach this needs.

---

## 7. UI / Template Changes

- `therapists/detail.html` — new "Employment Contract" card:
  - No contract yet → "Generate Contract" button (Owner only).
  - `DRAFT` pending → "Continue Draft" button.
  - `APPROVED` exists → summary (position, joining date, contract number, status) + "View" / "Renew" / "Cancel" buttons (Owner) or "View" only (the therapist themself, own record).
  - Collapsible "Contract History" list below, showing past `CANCELLED`/`SUPERSEDED` rows (view PDF only, no actions).
- `templates/contracts/form.html` — read-only therapist info panel (name, phone, email, address, specialization) at top, then the term-entry form (§4.1). Client-side JS shows/hides `probationPeriodMonths`/`probationSalary` based on the probation checkbox (mirrors existing show/hide patterns like the wallet pencil-edit toggles), and keeps `performanceBonusThreshold`/`performanceBonusAmount` paired (both-or-neither, inline validation message if only one is filled). `monthlySalary`'s label switches between "Monthly Salary" and "Monthly Salary (after probation)" based on the probation checkbox, live, no page reload. A small `*`-marked helper note under the Performance Bonus fields, e.g.:

  > *Paid as a one-time addition to that month's salary when the therapist completes at least the specified number of sessions within a calendar month; not paid in months where the threshold isn't met.*

- `templates/contracts/review.html` — the free-form review/edit screen: a rich-text editor pre-loaded with `contractBodyHtml`, "Save Draft" and "Approve" buttons. Needs a WYSIWYG editor dependency — see §10.
- `templates/contracts/detail.html` — metadata (contract number, status, dates, approved/cancelled/acknowledged-by info), an embedded/inline PDF viewer (`<embed>` or `<iframe>` pointing at `GET /contracts/{id}/pdf`), and role-appropriate action buttons (Renew/Cancel for Owner on an `APPROVED` row; Acknowledge for the therapist if not yet acknowledged).
- No new top-level nav item — reached only via the Therapist detail page, same convention as Wallet being reached only via the Patient detail page.

---

## 8. Reporting / Dashboard Impact

None. Contracts don't feed any report, KPI, or commission computation directly — only the one-time sync of `commissionPercent`/`monthlySalary`/bonus fields onto `Therapist` at approve-time (§5.4), after which those figures flow through the existing, unmodified `CommissionCalculator`/reports exactly as they do today.

---

## 9. Sample Contract Content (structure, for review)

Illustrative only — exact legal wording should be reviewed by the clinic owner before implementation. Merge fields in `{{double braces}}`; bracketed notes mark clauses that only appear when their backing field is set.

```
[Clinic Logo]
Healing House Clinic & Academy
1st Floor, IOB Complex, 86 MG Road, Muthialpet, Puducherry - 605003

EMPLOYMENT CONTRACT
Contract No: {{contractNumber}}                         Date: {{generatedDate}}

This Employment Contract ("Agreement") is entered into between Healing House Clinic
& Academy ("the Clinic") and {{therapist.fullName}} ("the Employee").

1. EMPLOYEE DETAILS
   Name:            {{therapist.fullName}}
   Phone:           {{therapist.phone}}
   Email:           {{therapist.email}}
   Address:         {{therapist.address}}

2. POSITION AND JOINING
   The Employee is engaged as {{position}}, effective from {{joiningDate}}.

   [if contractPeriodMonths set]
   This Agreement is valid for a period of {{contractPeriodMonths}} months from the
   date of joining, unless terminated earlier as per Clause 7.
   [else]
   This is an ongoing engagement, continuing until terminated by either party as per
   Clause 7.

   [if probationApplicable]
3. PROBATION
   The Employee shall be on probation for the first {{probationPeriodMonths}} months
   from the date of joining, during which the Employee shall be paid a monthly salary
   of ₹{{probationSalary}}.
   [end if]

4. COMPENSATION
   [if probationApplicable]
   Upon successful completion of the probation period, the Employee shall be paid a
   monthly salary of ₹{{monthlySalary}}.
   [else]
   The Employee shall be paid a monthly salary of ₹{{monthlySalary}}.

   [if commissionPercent set]
   In addition, the Employee shall be entitled to a commission of {{commissionPercent}}%
   on applicable service/product revenue, as per the Clinic's prevailing commission
   policy.
   [end if]

   [if performanceBonusThreshold and performanceBonusAmount set]
   The Employee shall be eligible for a monthly performance bonus of ₹{{performanceBonusAmount}}
   upon completing {{performanceBonusThreshold}} or more sessions within a calendar month.
   [end if]

5. WORKING CONDITIONS
   [standard clinic hours/conduct language — placeholder]

6. CONFIDENTIALITY
   [standard patient-confidentiality language — placeholder]

7. TERMINATION
   Either party may terminate this Agreement by providing {{noticePeriodMonths}}
   month(s) written notice.

For Healing House Clinic & Academy


_________________________
Authorized Signatory
[Owner Signature Image]
[Clinic Stamp Image]
```

---

## 10. Open Implementation Notes

- **New WYSIWYG dependency.** The free-form review/edit screen (§5.2, §7) needs a rich-text editor. Recommend a CDN-only option (e.g. Quill.js) to keep the app's existing "no npm/node build step" convention (Bootstrap/Chart.js/FullCalendar are all CDN today).
- **HTML → PDF is a new code path.** Every existing `PdfExportUtil.generate*Pdf` method builds a PDF from structured DTOs via iText's layout API directly (Paragraphs/Tables/Cells) — there's no precedent in this codebase for turning arbitrary owner-edited rich-text HTML into a PDF. Two options: (a) the iText `html2pdf` add-on, converting `contractBodyHtml` wrapped in a letterhead/signature template straight to PDF — least code, most faithful to whatever formatting the Owner applied; (b) parse the HTML and rebuild it with iText's own Paragraph/Table elements — more control, more work, and risks silently dropping owner formatting the parser doesn't understand. Recommend (a).
- **PDF stored as a DB `LONGBLOB`**, not a file. This app has no existing file-storage/upload infrastructure (confirmed non-goal in `Expenses_Requirements_v1.md` §2) — a DB blob avoids introducing one. Flagged: contract PDFs will grow the database/backup size over time, unlike every other report PDF in this app, which is generated on-demand and never persisted.
- **`Therapist.commissionRate` is a `[0,1]` fraction**; the contract form collects a whole-number percentage. The `÷100` conversion (§5.4) must happen before the existing `@DecimalMax("1")` validation runs, so an over-100% commission is rejected with a form error at approve-time, not a `ConstraintViolationException` from saving `Therapist`.
- **Contract numbering scheme** (`HHC-{year}-{sequence}`, §5.6) is a first pass — needs a concrete, race-safe implementation (e.g. a DB sequence/counter table, since two draft-generations in the same instant shouldn't collide) if the clinic ever runs multiple concurrent staff sessions creating contracts.

---

## 11. Decided (Open Questions Resolved)

Confirmed by the clinic owner during brainstorming, August 7, 2026:

- **Employee signature:** none on the PDF. Replaced by an in-app "Acknowledge" action (therapist clicks it after viewing their contract); recorded as `acknowledgedAt`/timestamp only, never printed on the document (§5.7).
- **Payroll sync:** approving a contract auto-writes `commissionPercent`/`monthlySalary`/`performanceBonusThreshold`/`performanceBonusAmount` into the corresponding `Therapist` fields (§5.4). The contract form, in turn, pre-populates those same fields from the therapist's *current* values when starting a fresh (non-renewal) contract (§4.1).
- **Admin access:** none. `CONTRACTS` is Owner-exclusive — Admin cannot even view a contract (§5.1).
- **Renewal/history model:** one `APPROVED` contract per therapist at a time; renewing auto-supersedes the previous one on approval of the new one; full history (`CANCELLED`/`SUPERSEDED`) stays viewable (§5.6).

### Flagged for explicit confirmation before implementation (reasonable defaults assumed, not directly discussed)

- **`monthlySalary` and `noticePeriodMonths` treated as mandatory** on the form, even though the original ask only explicitly marked Joining Date and Position as mandatory — assumed necessary since every contract needs a base salary figure and a termination-notice clause; flag if either should actually be optional/omittable.
- **`position` is free text**, not a dropdown of predefined roles — flag if a fixed position list (e.g. "Senior Therapist," "Junior Therapist," "Front Desk") is preferred instead.
- **`contractPeriodMonths` modeled as a duration in months** (not a specific end date) — consistent with probation/notice period also being month-counts; flag if a fixed calendar end date is actually wanted instead.
- **Cancelling a contract does not auto-modify the therapist's payroll fields** (§5.5) — they stay at their last-synced values until manually changed or a new contract is approved. Flag if cancellation should instead reset them.
- **Contract numbering scheme** `HHC-{year}-{sequence}` (§5.6, §10) — flag if a different format is preferred.
- **PDF stored as a DB blob**, not a file (§10) — flag if the clinic would rather this wait for/be bundled with a broader file-storage capability.
- **WYSIWYG editor choice deferred to implementation** (§10) — Quill.js suggested as a CDN-only fit for this app's no-build-step convention; flag if a specific editor is preferred.

---

*Document Version 1.0 — Healing House Clinic — August 2026*
