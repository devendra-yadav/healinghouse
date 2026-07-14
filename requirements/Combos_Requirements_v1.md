# Healing House Clinic — Service/Product Combos

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 13, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Adds a new **Combo** catalog module (siblings of `ClinicService`/`Product`) and modifies the **Appointment** line-item and discount flow (Phase 2, done).

---

## 1. Problem Statement

Today, staff build an appointment by adding services/products one at a time, each priced from the live catalog, with one optional whole-appointment discount. There is no way to package a fixed bundle of services and/or products (e.g. "Bridal Package: Facial + Massage + 1 Product") that patients can book as a single deal at a price lower than buying the items separately.

This document defines **Combos**: an admin-managed catalog of item bundles, each with a computed original price and an admin-set discounted price, selectable from the appointment form. Selecting a combo auto-populates its component service/product lines and applies the combo's own discount to just those lines — independent of, and layered underneath, the existing whole-appointment discount.

---

## 2. Goals

- A dedicated **Combos** admin page to create/edit/deactivate combos: pick any number of services and/or products (with quantity), see the computed original price live, and set a discount (percentage or flat ₹, reusing the existing `DiscountType`) to arrive at the combo's selling price.
- On the appointment form, a **"Choose Combo"** action lets staff pick one or more active combos; each selected combo expands into normal service/product lines, visually grouped, with its own discount already applied.
- Staff can still add standalone services/products alongside combo items in the same appointment.
- Multiple different combos can be added to the same appointment (Decided, §11).
- The appointment's `grandTotal` correctly reflects combo discounts **and**, if staff also enter one, the existing whole-appointment discount on top — both layers coexist without double-discounting or under-discounting.
- Which combo(s) were used, and what discount they carried, is persisted per appointment (Decided, §11) — enabling a savings badge on the appointment detail page now, and combo-performance reporting later without a schema change.
- Combo groups on an appointment are atomic once added: staff remove the whole combo, not individual items within it (Decided, §11) — see §5.4 for what this does and doesn't restrict.

### Non-goals (explicitly out of scope for this iteration)

- A dedicated combo-performance report page (e.g. "Combo X sold 14 times this month") — the data model supports it (§3.3), but building the report is a follow-up.
- Hard-deleting combos — mirrors the existing `ClinicService`/`Product`/`Therapist` pattern of an `active` flag only, never a destructive delete.
- Combo-level tags or categorization — combos are found by name/search on the picker; component services/products keep their own existing tags for commission purposes (§5.5).
- Time-limited or seasonal combos (auto-expiry) — a combo is simply active or inactive, toggled manually.
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 New entity: `Combo`

```java
@Entity
@Table(name = "combo", indexes = {
        @Index(name = "idx_combo_active", columnList = "active")
})
public class Combo {
    @Id @GeneratedValue
    private Long id;

    @NotBlank
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    private DiscountType discountType;      // NONE, PERCENTAGE, FLAT — reused from Appointment

    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;       // raw value staff typed, per discountType

    @OneToMany(mappedBy = "combo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComboServiceItem> serviceItems = new ArrayList<>();

    @OneToMany(mappedBy = "combo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComboProductItem> productItems = new ArrayList<>();
}
```

- **No stored "original price" or "combo price" column.** Both are computed transiently from the current catalog prices of `serviceItems`/`productItems` (`getOriginalPrice()`) minus the resolved discount (`getComboPrice()`), the same way `Appointment.getBalanceDue()` is `@Transient` today. This mirrors how catalog prices are always read live when adding a line to an appointment — a combo never goes stale relative to catalog price changes; only the *advertised* saving may shift slightly if a component's price changes, which is acceptable and consistent with existing snapshot-at-use-time philosophy (§5.2 explains what gets frozen once a combo is actually added to an appointment).
- No hard delete — only `active` toggle, matching `ClinicService`/`Product`/`Therapist`.

### 3.2 New entities: `ComboServiceItem` / `ComboProductItem`

Mirrors the existing `AppointmentServiceLine`/`AppointmentProductLine` split (separate tables per item type, not a polymorphic single table):

```java
@Entity
@Table(name = "combo_service_item")
public class ComboServiceItem {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Combo combo;

    @ManyToOne(optional = false)
    private ClinicService service;

    @Min(1)
    @Builder.Default
    private int quantity = 1;
}
```

```java
@Entity
@Table(name = "combo_product_item")
public class ComboProductItem {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(optional = false)
    private Combo combo;

    @ManyToOne(optional = false)
    private Product product;

    @Min(1)
    @Builder.Default
    private int quantity = 1;
}
```

### 3.3 New entity: `AppointmentCombo` (the "instance" of a combo applied to one appointment)

```java
@Entity
@Table(name = "appointment_combo")
public class AppointmentCombo {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "combo_id", nullable = false)
    private Combo combo;

    /** Combo name at the moment it was added — survives later renames of the Combo catalog entry. */
    @Column(nullable = false)
    private String comboNameSnapshot;

    @Enumerated(EnumType.STRING)
    private DiscountType discountType;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Resolved, capped rupee discount applied to this combo's own lines only. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountAmount;

    /** Sum of this combo's lines' raw lineTotal at the moment it was added — informational, for the savings badge. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalSubtotalSnapshot;
}
```

- This is the **per-appointment record of "combo X was applied here, with this discount"** — decoupled from the live `Combo` catalog entry the same way `AppointmentServiceLine.priceAtTime` is decoupled from `ClinicService.price`. If the combo is later edited or deactivated, past appointments' `AppointmentCombo` rows and savings figures are unaffected.
- `comboNameSnapshot` exists because `Combo` is never hard-deleted (§2 non-goals), so the FK is always resolvable — but a later rename shouldn't rewrite history on old appointment detail pages.

### 3.4 `AppointmentServiceLine` / `AppointmentProductLine` — one new nullable field each

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "appointment_combo_id")
private AppointmentCombo appointmentCombo;   // null for a standalone (non-combo) line
```

- Groups a combo's expanded lines together for display, removal (§5.4), and the two-phase discount distribution (§5.3). Standalone lines added outside any combo simply leave this `null`, unchanged from today's behavior.

### 3.5 ER Diagram Update

Add to the Mermaid diagram in `Healing_House_Clinic_Requirements_v1.md` §5:

```
COMBO ||--o{ COMBO_SERVICE_ITEM : "contains"
COMBO ||--o{ COMBO_PRODUCT_ITEM : "contains"
COMBO_SERVICE_ITEM }o--|| CLINIC_SERVICE : "references"
COMBO_PRODUCT_ITEM }o--|| PRODUCT : "references"
APPOINTMENT ||--o{ APPOINTMENT_COMBO : "combo instances used"
APPOINTMENT_COMBO }o--|| COMBO : "based on"
APPOINTMENT_COMBO ||--o{ APPOINTMENT_SERVICE : "this combo's service lines"
APPOINTMENT_COMBO ||--o{ APPOINTMENT_PRODUCT : "this combo's product lines"
```

### 3.6 Rollout

`hibernate.ddl-auto: update` auto-creates `combo`, `combo_service_item`, `combo_product_item`, `appointment_combo`, and adds the nullable `appointment_combo_id` FK to `appointment_service`/`appointment_product` — no backfill needed, every existing line simply has `appointmentCombo = null`.

---

## 4. DTO Changes

### 4.1 New: `ComboForm`

```java
private Long id;
private String name;
private String description;
private boolean active;
private List<ComboItemForm> serviceItems;   // {serviceId, quantity}
private List<ComboItemForm> productItems;   // {productId, quantity}
private String discountType;                // "NONE" | "PERCENTAGE" | "FLAT"
private BigDecimal discountValue;
```

`ComboItemForm { Long itemId; int quantity; }` — reused for both service and product rows client-side, same shape.

### 4.2 `AppointmentForm` — new field

```java
private List<ComboSelectionForm> comboSelections;   // combos added this submission
```

`ComboSelectionForm { Long comboId; String groupKey; }` — `groupKey` is a client-generated token (e.g. `combo-1`, `combo-2`) that the corresponding service/product line rows in the same submission carry in a hidden `comboGroupKey` field, so the server can regroup them into one `AppointmentCombo` per selection. The server **never trusts client-computed discount amounts** — it re-resolves each combo's `discountType`/`discountValue` from the live `Combo` catalog entry by `comboId` and recomputes the discount server-side, the same pattern already used for the whole-appointment discount (`AppointmentService.applyDiscount` resolves from raw `discountType`/`discountValue`, never a client-sent total).

### 4.3 `AppointmentServiceLine`/`AppointmentProductLine` form rows — one new field

```java
private String comboGroupKey;   // null for a standalone line; matches a ComboSelectionForm.groupKey
```

---

## 5. Business Rules

### 5.1 Combo definition (CRUD)

- A combo must have at least one item (service or product, any quantity) — same "can't save empty" guard pattern as elsewhere in the app.
- Original price = Σ (current `ClinicService.price` / `Product.price` × quantity) across all items, computed live wherever the combo is displayed (list, edit form, picker) — never stored.
- Discount resolution mirrors `AppointmentService.applyDiscount` exactly: `PERCENTAGE` capped at 100%, resolved ₹ amount capped at the original price (`resolved.min(originalPrice)`), so combo price can reach ₹0 but never go negative.
- Deactivating a combo (`active = false`) hides it from the appointment-form picker but has no effect on past appointments, since `AppointmentCombo` snapshots everything it needs (§3.3).

### 5.2 Adding a combo to an appointment

- Staff click **"Choose Combo"** on `appointments/form.html`, pick one or more active combos from a searchable modal (name, item summary, original price, combo price, savings badge) — same fragment/modal pattern as `fragments/wallet-modals.html`.
- Selecting a combo appends its service/product items to the form's existing line-item editor (reusing the current "add line" JS), each row priced from the **live catalog** at add time (same as any manually-added line — `priceAtTime` is a booking-time snapshot regardless of source), tagged with a shared `comboGroupKey` and shown in a visually distinct bordered group labeled with the combo's name.
- On save, `AppointmentService` groups incoming lines by `comboGroupKey`, creates one `AppointmentCombo` per group, resolves that combo's discount against the **sum of its own lines' raw `lineTotal`** (not the whole appointment), and distributes it across only those lines via `distributeDiscount` (see §5.3 for the required generalization of that method) — writing to each line's existing `discountedLineTotal` field, `appointmentCombo` FK set.
- Standalone lines (no `comboGroupKey`) behave exactly as they do today.

### 5.3 Layering: combo discount + whole-appointment discount

This is the key mechanics change to `AppointmentService`. Today, `distributeDiscount` always starts from each line's raw `getLineTotal()`. With combos, discounting now happens in **two ordered phases**, both writing to the same `discountedLineTotal` field, chained rather than overwritten:

1. **Per-combo phase** (§5.2): for each `AppointmentCombo` group, resolve its own discount against that group's raw subtotal and distribute across that group's lines only, starting from `getLineTotal()`. Lines outside any combo are untouched in this phase.
2. **Whole-appointment phase** (existing feature, generalized): `applyDiscount`/`distributeDiscount` must be changed to compute the appointment's subtotal as `Σ getEffectiveLineTotal()` across *all* lines (post-combo, where applicable) instead of `Σ getLineTotal()`, and to distribute starting from each line's **current** `getEffectiveLineTotal()` rather than raw `getLineTotal()`. This lets a manual whole-appointment discount apply on top of whatever combo discount a line already carries, proportionally, without re-discounting from scratch.
- `Appointment.grandTotal = subtotal(raw, all lines) − Σ(AppointmentCombo.discountAmount) − Appointment.discountAmount`. `Appointment.discountAmount` keeps its existing meaning (the manual whole-appointment discount only); combo discounts are tracked separately per-instance and summed via a new transient `Appointment.getTotalComboDiscount()`.
- Recalculation trigger: any time lines change (combo added/removed, standalone line added/removed) **or** the manual discount field changes, both phases re-run in order — same recalculation point `updateAppointment` already uses today for the existing discount, just now two-phase.
- This never touches `priceAtTime`, `lineTotal`, `totalServiceAmount`/`totalProductAmount` — same guarantee the core doc's existing Discounts rule makes, extended to combo discounts (§5.5).

### 5.4 Removing / editing a combo group on an appointment

- Combo groups are **locked as a unit** (Decided, §11): staff cannot change a combo line's service/product selection, quantity, or remove a single line out of the group. The only action available on a combo group is **"Remove combo"**, which deletes every line in that group plus its `AppointmentCombo` row and triggers recalculation (§5.3).
- **Per-line therapist reassignment remains allowed** on combo lines, same as any other line — it only affects commission attribution (who performed the work), not pricing or the combo's discount math, so it doesn't conflict with the "locked" pricing/composition rule above.
- Like all line-item edits today, combo add/remove is only permitted while the appointment is still `SCHEDULED`.

### 5.5 Commission — unaffected

- Combo discount is implemented purely through `discountedLineTotal` (§5.3), exactly like the existing whole-appointment discount. `priceAtTime`, `lineTotal`, and every commission/bonus query stay on undiscounted figures — a combo discount never reduces a therapist's commission, same guarantee as the core doc's Discounts business rule.
- Commission/bonus tag filtering (`Commission`/`Bonus` tags on `ClinicService`/`Product`) is unaffected — combo lines are ordinary `AppointmentServiceLine`/`AppointmentProductLine` rows and are picked up by the existing tag-filtered queries exactly like standalone lines.

### 5.6 Stock — unaffected

- Stock is still decremented per `AppointmentProductLine` only when the appointment is marked `COMPLETED`, regardless of whether the line came from a combo or was added standalone — no change to `AppointmentService`'s completion logic.

### 5.7 Double-booking conflicts — unaffected

- Combo lines carry a `therapist` like any line and participate in `findConflicts` exactly the same way; no special-casing needed.

---

## 6. Service / Controller Changes

### 6.1 `ComboService` (new)

- `create(ComboForm)`, `update(id, ComboForm)`, `deactivate(id)`, `findAllActive()`, `findById(id)`
- `computeOriginalPrice(Combo)` / `computeComboPrice(Combo)` — transient calculations shared by the combos list/edit pages and the appointment-form picker

### 6.2 `ComboController` (new)

- `GET /combos` — list (name, item count, original price, combo price, savings %, active toggle) — same layout convention as `services/list.html`/`products/list.html`, including pagination (per the recent pagination rollout across all lists).
- `GET /combos/new`, `POST /combos`, `GET /combos/{id}/edit`, `POST /combos/{id}`, `POST /combos/{id}/deactivate`
- `GET /combos/search?q=` — JSON suggestions for the appointment-form picker (name + summary + price), same pattern as `PatientController.search`/`TagController` autocomplete.

### 6.3 `AppointmentService` changes

- `createAppointment`/`updateAppointment`: after building service/product lines from the form, group the incoming rows by `comboGroupKey`, create/update `AppointmentCombo` rows, run the two-phase discount recalculation (§5.3).
- `applyDiscount`/`distributeDiscount`: generalized to operate on `getEffectiveLineTotal()` as the layering base, per §5.3.
- New: `applyComboDiscount(AppointmentCombo instance, List<line> itsLines)` — same resolve-and-cap logic as `applyDiscount`, scoped to one combo's lines.
- Combo removal path: delete the `AppointmentCombo` and its lines (cascade/orphanRemoval, mirroring how existing line removal already works), then re-run phase 2 (whole-appointment discount) since the subtotal changed.

---

## 7. UI / Template Changes

### 7.1 New `templates/combos/list.html` and `templates/combos/form.html`

- List: name, items summary (e.g. "Facial + Massage + 1 Product"), original price, combo price, savings badge, active toggle, edit link — same table/pagination/button conventions as `services/list.html` (`btn-sm btn-outline-secondary` view, `btn-sm btn-outline-primary` edit).
- Form: item picker for services and products (reuses the existing add-line UI pattern from `appointments/form.html`), live-computed original price, discount type/value inputs, live combo-price preview — mirrors the live discount preview already built for appointments.

### 7.2 `templates/appointments/form.html`

- New **"Choose Combo"** button opens a modal (active combos, searchable, showing price/savings) — new fragment, e.g. `fragments/combo-picker-modal.html`, same reusable-fragment convention as `fragments/wallet-modals.html`.
- Selected combo(s) render as visually grouped, bordered sections within the existing line-item editor, labeled with the combo name and showing the combo's own discount/savings; the group offers only a single "Remove combo" action (§5.4), not per-line remove.
- Grand total live-preview JS extended to sum: standalone lines + Σ(combo group totals, post their own discount) − whole-appointment discount, matching the two-phase server-side calculation (§5.3), so what staff see before submitting matches what gets saved.

### 7.3 `templates/appointments/detail.html`

- Each combo group displays as a bordered section (combo name, its lines, its discount/savings badge: "Combo savings: ₹X"), same visual language as the existing per-line strikethrough + discount badge for the whole-appointment discount.

---

## 8. Reporting / Dashboard Impact

- None required for v1 (non-goal, §2) — `DashboardService`/`ReportService` read off `Appointment.grandTotal` and per-line `priceAtTime`/tags exactly as today; combos don't change any revenue or commission calculation (§5.5).
- Flagged for a future iteration: since `AppointmentCombo` persists which combo was used and its discount, a "combo performance" report (usage count, total savings given, revenue attributed) can be built later with no schema change.

---

## 9. Acceptance Criteria

1. Staff can create, edit, and deactivate combos from a dedicated `/combos` page, picking any mix of services/products with quantities, and setting a percentage or flat discount off the computed original price.
2. On the appointment form, staff can add one or more combos via "Choose Combo"; each expands into its component service/product lines, grouped and priced with the combo's own discount applied.
3. Staff can still add standalone services/products alongside combo items in the same appointment.
4. If staff also enter a whole-appointment discount, it applies on top of any combo discounts already present, without double- or under-discounting — `grandTotal` is correct in both single-combo and multi-combo appointments.
5. A combo group can only be removed as a whole (not edited or partially removed) while the appointment is `SCHEDULED`; removing it correctly recalculates the appointment's totals.
6. Per-line therapist reassignment still works on combo lines.
7. Commission/bonus calculations are identical whether a line came from a combo or was added standalone — verified via existing `Commission`/`Bonus` tag filtering.
8. Stock decrement and double-booking conflict detection are unaffected by combo lines.
9. The appointment detail page shows each combo group with its savings; which combo(s) were used is retrievable from the database after the appointment is saved (`AppointmentCombo` rows).
10. No regressions to existing standalone-line appointments with no combos involved.

---

## 10. Open Implementation Note

`AppointmentService.applyDiscount`/`distributeDiscount` need real refactoring (not just additive changes) to support the two-phase layering in §5.3 — this touches the same code path the core doc's existing Discounts business rule and the Prepaid Balance wallet reversal logic both depend on (`AppointmentService.updateAppointment`'s recalculation point). Implementer should re-verify existing discount and wallet-reversal behavior (regression tests) after this refactor, not just add combo-specific tests.

---

## 11. Decided (Open Questions Resolved)

All confirmed by clinic owner, July 13, 2026:

- **Discount model:** Each combo tracks its own discount, distributed only across its own lines (`AppointmentCombo`); the existing whole-appointment discount still layers on top of everything (§5.3).
- **Multiple combos per appointment:** Supported — an appointment can contain several different combos plus standalone items (§2, §5.2).
- **Combo usage tracking:** Persisted via `AppointmentCombo`, enabling a savings badge now and combo-performance reporting later (§3.3, §8).
- **Editing a combo group on an appointment:** Locked — staff must remove the whole combo group at once, not edit/remove individual items within it; per-line therapist reassignment is still allowed since it doesn't affect combo pricing (§5.4).
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
