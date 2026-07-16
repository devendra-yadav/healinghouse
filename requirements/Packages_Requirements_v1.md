# Healing House Clinic — Service/Product Packages (Multi-Appointment Prepaid Sessions)

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 15, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Adds a new **Package** module tied to `Patient`, and modifies the **Appointment** line-item flow (Phase 2, done). Reuses mechanics from `Prepaid_Balance_Requirements_v1.md` (Wallet — ledger + reversal) and `Combos_Requirements_v1.md` (Combo — catalog + proportional price split).

---

## 1. Problem Statement

A patient can currently only pay for services/products at the appointment where they're delivered. There is no way for a patient to prepay for a **bundle of future sessions** — e.g. 10 back massages, 7 acupuncture sessions, and 5 cupping sessions, bought together, to be delivered across many separate future appointments over weeks or months.

This is distinct from the existing **Wallet** (an open ₹ balance, drawn down by any amount toward any appointment) and the existing **Combo** (a bundle consumed entirely within one appointment). A **Package** is a bundle of specific services/products, prepaid once, whose individual session counts are drawn down **one unit at a time, across many future appointments**, until exhausted.

This document defines **Packages**: a staff-manageable catalog of reusable package templates (optional convenience), a per-patient purchase record with per-item remaining-session counts, and a new "Already Paid" section on the appointment form that lets staff add an already-paid session to any appointment with one click.

---

## 2. Goals

- Staff can sell a patient a package: a named bundle of services/products, each with a session count (e.g. "10x Back Massage, 7x Acupuncture, 5x Cupping"), for one total ₹ price.
- Packages can be sold either from a staff-predefined **template** (one-click, pre-filled) or built **custom** on the spot for a one-off request — both produce the same kind of record (Decided, §11).
- Every purchased package is recorded per-patient with remaining session counts per item, visible on the patient detail page.
- When booking or editing *any* appointment for that patient, a dedicated section shows every service/product the patient still has paid-for sessions remaining for (pooled across all their active, unexpired packages), each addable to the appointment with one click. Adding one reduces the pooled remaining count by exactly one unit.
- If a patient has the same service across multiple package purchases, the counts pool into a single number, and consumption always draws from the **oldest unexpired purchase first (FIFO)** (Decided, §11).
- A package-covered line behaves like a normal line for commission purposes — the therapist earns commission on it exactly as if it were paid in cash (Decided, §11).
- Package sale itself is **not revenue**; revenue is recognized only when a package-covered session is delivered at a `COMPLETED` appointment — same accrual principle as the Wallet feature (Decided, §11).
- Packages can optionally expire (staff sets an expiry date per sale, or leaves it open-ended); nothing is auto-forfeited — unused value is refunded manually by staff if needed (Decided, §11).
- Every purchase, consumption, reversal, and refund is recorded in an auditable per-package ledger, mirroring `WalletTransaction`.

### Non-goals (explicitly out of scope for this iteration)

- **Package ⨯ Combo interaction** — a package-covered line is always added standalone via the new "Already Paid" section, never through the Combo picker, and a package cannot contain a `Combo` as an item (only individual `ClinicService`/`Product` rows, same granularity as `ComboServiceItem`/`ComboProductItem`). Combining the two is a future iteration.
- **Partial-line package coverage** — a package always covers a line's *entire* value (1 click = 1 full session drawn down). There is no "apply ₹300 of package value toward a ₹500 line," unlike Wallet's arbitrary-amount model.
- **Installment payment for a package purchase** — a package sale is recorded as paid in full, in one `PaymentMethod`, at time of sale (mirrors Wallet's top-up: one payment method per transaction, no splitting). If a clinic wants to sell packages on a deposit/installment basis, that's a future iteration.
- **Auto-forfeiture on expiry** — an expired package simply stops appearing in the "Already Paid" pooled list; nothing happens to its ledger/value automatically. Refund is always a manual staff action (§5.7).
- **Package-performance reporting page** (e.g. "which packages sell best") — data model supports it via the ledger, but building the report is a follow-up, same deferral pattern as Combo's non-goal (§8 of `Combos_Requirements_v1.md`).
- **Per-item partial refund** — refund/cancellation acts on the whole `PatientPackage` at once (all its remaining items), not one item within it. Simpler unit of cancellation, consistent with treating "the package" as the sold thing.
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 New entities: `PackageTemplate` / `PackageTemplateServiceItem` / `PackageTemplateProductItem`

Optional, staff-managed catalog — mirrors `Combo`/`ComboServiceItem`/`ComboProductItem` exactly, including the "no stored final price, computed live" philosophy for the *suggested* price:

```java
@Entity
@Table(name = "package_template", indexes = {
        @Index(name = "idx_package_template_active", columnList = "active")
})
public class PackageTemplate {
    @Id @GeneratedValue
    private Long id;

    @NotBlank
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    private boolean active = true;

    // Suggested price, resolved the same way Combo resolves its price: from the live catalog
    // sum of (item price x sessionCount), discounted by this. Purely a starting point at sale
    // time (§5.2) — staff can override the total before saving the actual PatientPackage.
    @Enumerated(EnumType.STRING)
    private DiscountType discountType;
    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;

    @OneToMany(mappedBy = "packageTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackageTemplateServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "packageTemplate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackageTemplateProductItem> productItems = new ArrayList<>();
}
```

```java
@Entity
@Table(name = "package_template_service_item")
public class PackageTemplateServiceItem {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private PackageTemplate packageTemplate;

    @ManyToOne(optional = false)
    private ClinicService service;

    @Min(1)
    private int sessionCount;   // e.g. 10, for "10x Back Massage"
}
```

`PackageTemplateProductItem` is the product-side mirror (same shape, `Product` instead of `ClinicService`).

- No hard delete — `active` toggle only, same as `ClinicService`/`Product`/`Combo`.
- `computeSuggestedPrice(PackageTemplate)` = Σ(current catalog price × sessionCount) across all items, discounted by `discountType`/`discountValue` — same resolution/capping logic as `ComboService.computeComboPrice`. This is only ever a *starting point*; it is never persisted and never binds a later `PatientPackage` sale.

### 3.2 New entities: `PatientPackage` / `PatientPackageServiceItem` / `PatientPackageProductItem`

The actual sold instance:

```java
@Entity
@Table(name = "patient_package")
public class PatientPackage {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    /** Set only if sold from a template; null for a fully custom package. Informational only —
     *  never re-read after sale, so a later template edit/deactivation cannot affect this row. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_template_id")
    private PackageTemplate sourceTemplate;

    @Column(nullable = false)
    private String name;   // copied from template name, or staff-entered for custom packages

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;   // what the patient actually paid, in full, at sale

    @Column(nullable = true)
    private LocalDate expiryDate;   // null = never expires

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PatientPackageStatus status;   // ACTIVE, COMPLETED, EXPIRED, CANCELLED

    @Version
    private Long version;   // guards concurrent consumption racing against cancellation/refund

    @CreationTimestamp
    private LocalDateTime purchasedAt;

    @OneToMany(mappedBy = "patientPackage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PatientPackageServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "patientPackage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PatientPackageProductItem> productItems = new ArrayList<>();
}
```

```java
@Entity
@Table(name = "patient_package_service_item")
public class PatientPackageServiceItem {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_package_id", nullable = false)
    private PatientPackage patientPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private ClinicService service;

    @Column(nullable = false)
    private int sessionsTotal;

    @Column(nullable = false)
    @Builder.Default
    private int sessionsUsed = 0;

    /** This item's proportional share of totalPrice, resolved at sale time via the same
     *  sort-ascending/last-absorbs-remainder allocator Combo/discount distribution already uses.
     *  Drives both revenue-on-consumption bookkeeping (informational — see §5.9) and refund math (§5.7). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAllocated;

    @Transient
    public int getSessionsRemaining() { return sessionsTotal - sessionsUsed; }
}
```

`PatientPackageProductItem` is the product-side mirror.

```java
public enum PatientPackageStatus {
    ACTIVE,     // has at least one item with sessionsRemaining > 0, not expired, not cancelled
    COMPLETED,  // every item's sessionsUsed == sessionsTotal
    EXPIRED,    // expiryDate has passed with sessionsRemaining > 0 on at least one item
    CANCELLED   // staff explicitly cancelled (§5.7)
}
```

- `status` is maintained transactionally at each consumption/reversal/refund (not a scheduled job) — computed and persisted the same way `Appointment.getPaymentStatus()`-style derivations are handled elsewhere, except here it's a real stored column since it needs to be filterable/indexable for the pooled-balance query (§5.3). EXPIRED is additionally checked lazily whenever the pooled-balance query runs (`expiryDate < today`), so a package can flip ACTIVE→EXPIRED between writes without a background job — same "lazy invariant check" spirit as `PatientWallet` never spontaneously going negative.

### 3.3 New entity: `PackageTransaction` (the ledger — mirrors `WalletTransaction`)

```java
@Entity
@Table(name = "package_transaction")
public class PackageTransaction {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_package_id", nullable = false)
    private PatientPackage patientPackage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PackageTransactionType type;   // PURCHASE, USAGE, REVERSAL, REFUND

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;   // always positive

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;   // set for PURCHASE and REFUND only

    // Set for USAGE/REVERSAL only — exactly one of the pairs below is non-null, matching
    // whichever the drawn-down item/line actually was:
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_package_service_item_id")
    private PatientPackageServiceItem patientPackageServiceItem;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_package_product_item_id")
    private PatientPackageProductItem patientPackageProductItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;   // set for USAGE/REVERSAL only

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

```java
public enum PackageTransactionType {
    PURCHASE,   // package sold, real money received — reconciliation event, not revenue (§5.9)
    USAGE,      // sessionsUsed += 1 on one item, drawn against an appointment line
    REVERSAL,   // sessionsUsed -= 1, a previous USAGE unwound (line removed / appointment cancelled)
    REFUND      // real money paid back to patient for unused remaining value (§5.7)
}
```

### 3.4 `AppointmentServiceLine` / `AppointmentProductLine` — one new nullable field each

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "patient_package_service_item_id")
private PatientPackageServiceItem packageServiceItem;   // null for a normally-paid line
```

(`AppointmentProductLine` gets the equivalent `packageProductItem` FK.)

- Records **which specific package item** this line drew from — the exact item FIFO picked at consumption time (§5.3), not just "this was package-covered." Needed so reversal (§5.5) restores the correct item even if, by the time of reversal, pooling/FIFO would now pick a different one.
- A line with this set is what marks it "already paid" — drives both the `Appointment.packageAmountApplied` computation (§3.5) and the appointment detail page's display (§7.3).

### 3.5 `Appointment` — one new field

```java
@Column(precision = 12, scale = 2)
@Builder.Default
private BigDecimal packageAmountApplied = BigDecimal.ZERO;
```

- Sum of `priceAtTime` across every line on this appointment that carries a `packageServiceItem`/`packageProductItem` — i.e. the portion of `grandTotal` already fully settled by package sessions. Unlike `walletAmountApplied` (an arbitrary chosen amount), this is **always exactly the sum of the covered lines' own values** — there is no partial coverage (§2 non-goals).
- `amountPaid = packageAmountApplied + walletAmountApplied + <cash/UPI/card/etc.>` — same additive composition Wallet already established (`Prepaid_Balance_Requirements_v1.md` §3.3), now with a third source.

### 3.6 ER Diagram Update

```
PATIENT ||--o{ PATIENT_PACKAGE : "purchases"
PATIENT_PACKAGE ||--o{ PATIENT_PACKAGE_SERVICE_ITEM : "contains"
PATIENT_PACKAGE ||--o{ PATIENT_PACKAGE_PRODUCT_ITEM : "contains"
PATIENT_PACKAGE ||--o{ PACKAGE_TRANSACTION : "ledger"
PACKAGE_TEMPLATE ||--o{ PACKAGE_TEMPLATE_SERVICE_ITEM : "contains"
PACKAGE_TEMPLATE ||--o{ PACKAGE_TEMPLATE_PRODUCT_ITEM : "contains"
PATIENT_PACKAGE }o--o| PACKAGE_TEMPLATE : "sold from (optional)"
APPOINTMENT_SERVICE }o--o| PATIENT_PACKAGE_SERVICE_ITEM : "fulfilled from"
APPOINTMENT_PRODUCT }o--o| PATIENT_PACKAGE_PRODUCT_ITEM : "fulfilled from"
APPOINTMENT ||--o{ PACKAGE_TRANSACTION : "USAGE/REVERSAL source"
```

### 3.7 Rollout

`hibernate.ddl-auto: update` auto-creates all new tables and adds nullable FK columns to `appointment_service`/`appointment_product` plus `package_amount_applied` (default `0.00`) to `appointment` — no backfill needed, every existing row simply has these as `null`/`0`.

---

## 4. DTO Changes

### 4.1 New: `PackageTemplateForm`

```java
private Long id;
private String name;
private String description;
private boolean active;
private List<PackageTemplateItemForm> serviceItems;   // {serviceId, sessionCount}
private List<PackageTemplateItemForm> productItems;   // {productId, sessionCount}
private String discountType;
private BigDecimal discountValue;
```

### 4.2 New: `PackageSaleForm`

```java
private Long patientId;
private Long sourceTemplateId;              // nullable — set if starting from a template
private String name;                        // required; pre-filled from template, editable
private List<PackageSaleItemForm> serviceItems;   // {serviceId, sessionCount} — pre-filled from template, editable
private List<PackageSaleItemForm> productItems;   // {productId, sessionCount}
private BigDecimal totalPrice;              // required, > 0 — pre-filled from computeSuggestedPrice(), editable
private LocalDate expiryDate;               // nullable
private PaymentMethod paymentMethod;        // required — how the package was paid for
private String note;
```

At least one service or product item with `sessionCount >= 1` is required — same "can't save empty" guard as Combo.

### 4.3 New: `PackageRefundForm`

```java
private Long patientPackageId;
private BigDecimal amount;          // required, > 0, validated <= remaining refundable value (§5.7)
private PaymentMethod paymentMethod;
private String note;
```

### 4.4 `AppointmentServiceLine`/`AppointmentProductLine` form rows — one new field

```java
private Long packageItemId;   // null for a normal line; set when added via the "Already Paid" section
```

Mirrors how `comboGroupKey` marks a combo-sourced line (`Combos_Requirements_v1.md` §4.3) — the server re-validates and re-resolves against the live `PatientPackageServiceItem`/`ProductItem` by id, never trusting a client-sent "this is a package line" flag without it.

---

## 5. Business Rules

### 5.1 Package template definition (CRUD) — optional convenience layer

- A template must have at least one item (service or product, any session count).
- `computeSuggestedPrice(template)` = Σ(current catalog price × sessionCount) discounted by `discountType`/`discountValue`, same resolution/capping as `ComboService.computeComboPrice` (percentage capped at 100%, resolved ₹ capped at the raw sum).
- Deactivating a template hides it from the sale-flow picker; has no effect on any `PatientPackage` already sold from it, since `PatientPackage` copies everything it needs at sale time (name, items, price) and only keeps `sourceTemplate` as an informational backlink.

### 5.2 Selling a package

- From the patient detail page, staff click **"Sell Package"**, opening a modal/page: pick a template (pre-fills name/items/suggested price, all fields still editable) or start blank and add service/product rows with session counts directly.
- On save: `PatientPackage` is created with `status = ACTIVE`; each item's `priceAllocated` is resolved by distributing `totalPrice` across items proportional to each item's `(catalogPrice × sessionCount)` share of the raw sum — reusing the existing `distributeAmount` allocator (sort-ascending, last item absorbs the rounding remainder) already used for discount/combo distribution.
- A `PURCHASE` `PackageTransaction` is created for the full `totalPrice`, carrying `paymentMethod` — this is the cash-reconciliation event (§8.2), not a revenue event (§5.9).

### 5.3 Pooling and FIFO consumption

- The appointment form's "Already Paid" section queries, per patient, every `ACTIVE` `PatientPackage` with `expiryDate` null or in the future, and aggregates `sessionsRemaining` **per distinct service/product** across all of them into one pooled number (e.g. "Back Massage — 6 remaining").
- When staff click to add a pooled item to the current appointment, the server resolves which specific `PatientPackageServiceItem`/`ProductItem` to draw from by **oldest-purchase-first (FIFO)**: order the patient's eligible items for that service/product by their parent `PatientPackage.purchasedAt` ascending, and draw from the first one with `sessionsRemaining > 0`.
- This resolution happens **server-side at save time**, not merely for display — the same "never trust a client-sent identity for something server-authoritative" principle already used for combo discounts (`Combos_Requirements_v1.md` §4.2). The client only needs to say "add one Back Massage from package," not which specific purchase.
- If, between page load and save, the previously-eligible item became insufficient (e.g. another appointment for the same patient consumed the last session concurrently), the save fails with a clear validation error and the form re-renders with entered data preserved — same UX pattern as a double-booking conflict re-render.

### 5.4 Adding a package-covered line to an appointment

- Clicking an "Already Paid" item appends one line to the form's existing line-item editor (reusing the current add-line UI), pre-filled at the item's **current catalog price** (`priceAtTime` = live `ClinicService`/`Product` price at add time — same "booking-time snapshot" rule every other line already follows, regardless of source), visually badged "Package" and showing which purchase-pool it's drawing from.
- On save, the server resolves the FIFO item (§5.3), sets the line's `packageServiceItem`/`packageProductItem` FK, increments that item's `sessionsUsed`, creates a `USAGE` `PackageTransaction` (amount = the line's `priceAtTime`, linked to the appointment and the line), and adds the line's `priceAtTime` into `Appointment.packageAmountApplied`.
- A package-covered line still carries a `therapist` (defaulting to the appointment's main therapist, reassignable per line) exactly like any other line — commission attribution is unaffected (§5.8).
- Staff can still add standalone (normally paid) and combo lines in the same appointment as package-covered lines — no restriction between the three line sources.

### 5.5 Editing / cancelling appointments — reversal, and the clear-and-rebuild interaction

- General rule, symmetric with Wallet (`Prepaid_Balance_Requirements_v1.md` §5.4): whenever a package-covered line stops being on the appointment, its consumption is reversed — `sessionsUsed -= 1` on the exact item it was drawn from (recorded on the line's FK, §3.4 — **not** re-run through FIFO), `packageAmountApplied` reduced by that line's value, a `REVERSAL` `PackageTransaction` logged.
- Triggers: staff removes the line on edit; the appointment is cancelled or marked `NO_SHOW` (reverse every package-covered line on it, mirroring `reverseFullWalletIfAny`); line quantity/service changes (treated as remove-old + possibly-add-new, same as today).
- **Interaction with the existing full clear-and-rebuild on every save** (`AppointmentService.updateAppointment` already clears and rebuilds `serviceLines`/`productLines` from the submitted form every time, per the Combos business rule in the core doc): the submitted form for each package-covered line must carry back the **specific `packageServiceItem`/`packageProductItem` id** it was already drawn from (§4.4's `packageItemId`), not just "this is a package line." On rebuild:
  - A line resubmitted with the *same* `packageItemId` it already had is a no-op for consumption bookkeeping — do not reverse-then-reapply, since that risks a stale double-decrement or, if another appointment consumed the same pooled service in the meantime, FIFO landing on a *different* item the second time around.
  - A line with no `packageItemId` previously and one newly present is a fresh consumption (§5.4).
  - A previously-package-covered line **absent** from the resubmitted form triggers reversal, exactly as if explicitly removed.
- Like all line-item edits today, this only applies while the appointment is `SCHEDULED` (or on the cancel/no-show transition, which is always permitted).

### 5.6 Expiry

- `expiryDate` is optional per `PatientPackage`, set at sale (§4.2). No background job — expiry is enforced lazily wherever eligibility is checked: the pooled-balance query (§5.3) excludes any `PatientPackage` with a past `expiryDate`, and `status` flips to `EXPIRED` the next time that package is touched (viewed on patient detail, or evaluated by the pooled query) if it still has `sessionsRemaining > 0` on any item.
- An expired package's unused sessions are **not** automatically forfeited or refunded (§2 non-goals) — they simply stop being offered on the appointment form. Staff must explicitly refund (§5.7) if the clinic wants to settle up with the patient, or reactivate manually if the app later supports that (not in this iteration).

### 5.7 Refund / cancellation

- Staff can cancel a `PatientPackage` (`ACTIVE` or `EXPIRED` → `CANCELLED`) from the patient detail page at any time, refunding some or all of its remaining value.
- **Refundable value** = Σ across all items of `priceAllocated × sessionsRemaining / sessionsTotal` (proportional remaining value per item, summed) — a transient calculation, same "derive, don't store" pattern as `Appointment.getBalanceDue()`.
- A `REFUND` `PackageTransaction` is created for the amount actually refunded (staff can refund less than the full refundable value, e.g. a partial goodwill refund), carrying `paymentMethod` for cash reconciliation. This does **not** change `sessionsUsed`/`sessionsRemaining` on the items — cancellation makes the package ineligible for further consumption (§5.3's query already excludes non-`ACTIVE` packages) independent of how much was refunded.
- No connection to a specific `PURCHASE` transaction, same as Wallet refunds not tracing back to a specific top-up (`Prepaid_Balance_Requirements_v1.md` §5.5).

### 5.8 Commission — unaffected

- A package-covered line's `priceAtTime`/`lineTotal` is the full current catalog price, exactly like any standalone line. `CommissionCalculator`'s `Commission`/`Bonus` tag-filtered queries pick it up identically regardless of source (standalone, combo, or package) — commission is **never** reduced because a session was pre-paid, same guarantee the core doc's Discounts rule already makes for whole-appointment/combo discounts.

### 5.9 Revenue recognition

- `PURCHASE` and `REFUND` `PackageTransaction`s are **never** revenue — a package sale is a liability (services owed), exactly like a Wallet top-up (`Prepaid_Balance_Requirements_v1.md` §5.3).
- Revenue continues to be recognized exactly as today: off `Appointment.grandTotal` at the point an appointment is `COMPLETED`, regardless of whether a given line was paid in cash, via wallet, or via a package session. A package-covered line's `priceAtTime` contributes to `grandTotal`/the Actual Revenue report at that appointment's completion, same timing as any other line — this is what makes package revenue "recognized on consumption" rather than at the (earlier, larger) point of sale.
- Cash-reconciliation reporting must include `PURCHASE` (money in) and `REFUND` (money out) `PackageTransaction`s, separately from appointment revenue — same flag already raised for Wallet (§8.2 below).

### 5.10 Stock — unaffected

- A package-covered `AppointmentProductLine` still decrements stock only when its appointment is marked `COMPLETED` — stock reflects physical hand-over of the product, not when it was paid for. Buying a package of products does not touch stock at sale time.

### 5.11 Double-booking conflicts — unaffected

- Package-covered lines carry a `therapist` like any line and participate in `findConflicts` exactly the same way; no special-casing needed.

### 5.12 Interaction with Combos — none in this iteration

- A package-covered line is always added standalone via the "Already Paid" section, never through the Combo picker. A `PackageTemplateServiceItem`/`ProductItem` or `PatientPackageServiceItem`/`ProductItem` references a raw `ClinicService`/`Product` only, never a `Combo`. Revisit if a future request needs "package of combos."

---

## 6. Service / Controller Changes

### 6.1 `PackageTemplateService` (new)

- `create(PackageTemplateForm)`, `update(id, form)`, `deactivate(id)`, `findAllActive()`, `findById(id)`
- `computeSuggestedPrice(PackageTemplate)` — shared by the templates management page and the sell-package modal's pre-fill

### 6.2 `PackageTemplateController` (new)

- `GET /package-templates`, `GET /package-templates/new`, `POST /package-templates`, `GET /package-templates/{id}/edit`, `POST /package-templates/{id}`, `POST /package-templates/{id}/deactivate` — same conventions as `ComboController`.

### 6.3 `PackageService` (new)

- `sellPackage(PackageSaleForm)` → creates `PatientPackage` + items (proportional `priceAllocated` split) + `PURCHASE` transaction
- `getPooledAvailability(patientId)` → `List<PackageAvailabilityDTO>` (service/product, pooled sessionsRemaining) for the appointment form's "Already Paid" section
- `resolveAndConsume(patientId, serviceId|productId, appointmentId, lineId)` → FIFO-resolves the item (§5.3), increments `sessionsUsed`, logs `USAGE` — called from `AppointmentService` during save
- `reverseConsumption(packageServiceItem|packageProductItem, appointmentId, lineId)` → decrements `sessionsUsed`, logs `REVERSAL`
- `refund(patientPackageId, amount, paymentMethod, note)` → validates `<=` refundable value (§5.7), logs `REFUND`, sets `status = CANCELLED`
- `getTransactionHistory(patientPackageId, pageable)` / `getAllForPatient(patientId)` — feeds the patient detail package card

### 6.4 `PackageController` (new)

- `POST /patients/{id}/packages` — sell (`PackageSaleForm`), redirects back with flash message.
- `POST /patients/{id}/packages/{packageId}/refund` — `PackageRefundForm`.
- `GET /patients/{id}/packages/available` — JSON, feeds the appointment form's "Already Paid" section (`PackageAvailabilityDTO` list), same `@ResponseBody`-JSON-on-error pattern as the existing typeahead endpoints (`GlobalExceptionHandler`'s JSON branch).

### 6.5 `AppointmentService` integration

- `createAppointment`/`updateAppointment`: for each submitted line carrying a `packageItemId`, either treat as a no-op (id matches the line's existing FK — §5.5) or call `PackageService.resolveAndConsume` (new/changed) or `reverseConsumption` (dropped from the resubmitted form). Runs alongside the existing wallet reconciliation step, after `grandTotal` is finalized.
- Status transitions to `CANCELLED`/`NO_SHOW` must call `reverseConsumption` for every still-package-covered line on the appointment, mirroring the existing wallet reversal call site.

---

## 7. UI / Template Changes

### 7.1 New `templates/package-templates/list.html` and `.../form.html`

- Same list/form conventions as `templates/combos/`: item picker with session counts instead of quantities, live suggested-price preview.

### 7.2 `templates/patients/detail.html`

- New "Packages" card (alongside the existing Wallet card): "Sell Package" button, a list of the patient's packages (name, status badge, per-item remaining/total, expiry if set, refundable value if cancellable) and a paginated `PackageTransaction` history table — same pattern as the Wallet card.
- "Sell Package" opens a modal/page: template picker (optional) + editable item/session-count rows + total price + expiry + payment method, mirroring `fragments/wallet-modals.html`'s top-up modal structure.

### 7.3 `templates/appointments/form.html`

- New **"Already Paid"** section (visible once a patient is selected): one row per service/product the patient has pooled `sessionsRemaining > 0` for, each with an "Add" button. Clicking appends a line to the existing editor, badged "Package," non-removable-price (its value is fixed at the live catalog price, same as any line, but the amount-owed side shows ₹0 due for it).
- Grand-total live-preview JS extended so package-covered lines still count toward the displayed `grandTotal` (for correct commission-adjacent totals) but are excluded from the "amount still owed" figure the same way wallet-applied amounts already are.

### 7.4 `templates/appointments/detail.html`

- Package-covered lines show a "Paid via Package" badge (parallel to the existing wallet "Paid from wallet" row and combo savings badge), naming which package it drew from.

---

## 8. Reporting / Dashboard Impact

### 8.1 `DashboardService`/reports — unaffected for revenue

- Revenue KPIs/trend and every report's `grandTotal`-based figures are unaffected by this feature per §5.9 — a package-funded line counts as revenue exactly like a cash-funded one, at the same point (appointment completion) it does today.

### 8.2 New consideration: cash reconciliation

- Same flag already raised for Wallet (`Prepaid_Balance_Requirements_v1.md` §8.2): any "money physically received today" reconciliation report must include `PURCHASE` transactions as money in and `REFUND` transactions as money out, separately from appointment revenue. This document flags the need; it does not define a new report page (non-goal, §2).

### 8.3 Optional dashboard tile (implementer's judgment, not required for v1)

- "Total outstanding package liability" (Σ `priceAllocated × sessionsRemaining/sessionsTotal` across all `ACTIVE` packages) — the services-owed counterpart to the Wallet feature's outstanding-balance tile. Flagged as a natural low-cost addition, not required for acceptance.

---

## 9. Acceptance Criteria

1. Staff can define reusable package templates (`/package-templates`), each a named bundle of services/products with session counts and a computed suggested price.
2. Staff can sell a patient a package from the patient detail page, either from a template (pre-filled, still editable) or fully custom, recording total price and payment method in one transaction.
3. A patient's packages, statuses, and per-item remaining/total counts are visible on their detail page, along with a full transaction history.
4. On the appointment form, once a patient is selected, an "Already Paid" section shows every service/product they have pooled remaining sessions for (summed across all their active, unexpired packages), addable with one click.
5. Adding a package item to an appointment consumes exactly one session from the oldest eligible unexpired purchase (FIFO), fully covering that line's value — no additional payment required for that line.
6. Editing an appointment to remove a package-covered line, or cancelling/no-showing the appointment, automatically restores the session to the exact package item it was drawn from.
7. Re-saving an appointment without changing its existing package-covered lines does not double-consume or spuriously reverse sessions.
8. Package sales and refunds are never counted as revenue in the dashboard or any report; revenue is recognized only when a package-covered line's appointment is `COMPLETED`, identical timing to any other line.
9. Commission calculations are identical whether a line was paid in cash, via wallet, via combo, or via package — verified via existing `Commission`/`Bonus` tag filtering.
10. Packages with an expiry date stop appearing in the "Already Paid" section once expired, without any automatic change to their ledger or stored value.
11. Staff can cancel a package and refund some or all of its remaining value, recording the payout method; this appears in the package's transaction history and does not alter session counts.
12. No regressions to existing standalone, wallet-funded, or combo-funded appointment flows.

---

## 10. Open Implementation Notes

- **FIFO resolution must be server-side and re-checked at save time**, not merely computed for display — same principle already required for combo discounts (`Combos_Requirements_v1.md` §4.2) and double-booking conflicts. A stale client view of "6 remaining" must not be trusted if it's since dropped to 5.
- **The clear-and-rebuild interaction (§5.5) is the trickiest part of this feature** — same caliber of risk `Combos_Requirements_v1.md` §10 flagged for its own discount-layering refactor. The line-level `packageItemId` round-trip (§4.4) is what makes re-saving an unchanged appointment idempotent; skipping it would either double-decrement session counts or spuriously reverse-and-reapply against a different FIFO-picked item. Needs explicit regression coverage.
- Reuses `distributeAmount` (introduced for Combos/discounts) for splitting a package's `totalPrice` across its items — no new allocation algorithm needed.

---

## 11. Decided (Open Questions Resolved)

All confirmed by clinic owner, July 15, 2026:

- **Composition:** A package is a bundle of any mix of services/products, each with its own session count (§2, §3.2).
- **Pooling:** Same service/product across multiple package purchases pools into one number on the appointment form; consumption always draws from the oldest unexpired purchase first, FIFO (§5.3).
- **Source:** Both staff-predefined templates (one-click, pre-filled) and fully custom on-the-spot packages are supported (§5.1, §5.2).
- **Commission:** A package-covered session earns commission exactly like a normally-paid one — commission is computed off the line's full catalog price regardless of payment source (§5.8).
- **Revenue timing:** Package sale is a liability, not revenue; revenue is recognized only when a package-covered line's appointment completes (§5.9).
- **Expiry:** Optional, configurable per package at sale time; no auto-forfeiture — unused value requires a manual staff refund (§5.6, §5.7).
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

### Flagged for explicit confirmation before implementation (reasonable defaults assumed, not directly discussed)

- **Package purchase is paid in full, in one payment method, at time of sale** — no installment/deposit model (assumed, mirroring Wallet's top-up simplicity). Flag if the clinic actually wants partial-payment packages.
- **Cancellation/refund is whole-package, not per-item** — cancelling a package forfeits eligibility on all its items at once, even if staff only wants to refund one service within a multi-service package (assumed for simplicity, §2 non-goals). Flag if per-item refund is actually needed.
- **One click = exactly one session consumed per line** — no way to draw multiple sessions of the same item into a single appointment line in one action; staff would click "Add" twice to use two sessions in one visit (assumed, §2 non-goals).

---

*Document Version 1.0 — Healing House Clinic — July 2026*
