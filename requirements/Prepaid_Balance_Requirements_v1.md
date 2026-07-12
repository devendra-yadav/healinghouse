# Healing House Clinic — Patient Prepaid Balance (Wallet)

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 12, 2026
**Status:** Draft — open questions resolved, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Adds a new **Wallet** module tied to `Patient`, and modifies the **Appointment** payment flow (Phase 2, done) and **Dashboard/Reports** (Phase 3, done).

---

## 1. Problem Statement

A patient can currently only pay for an appointment at the time of that appointment (`Appointment.amountPaid`, capped at `grandTotal`). There is no way for a patient to pre-fund a balance (e.g. buy a ₹10,000 package upfront) and draw it down across future visits, topping up again once it runs low.

This document defines a per-patient **prepaid balance** ("wallet"): patients can top it up at any time; appointment bills can be settled from it first, with any shortfall collected the normal way (cash/UPI/card/etc.); unused balance can be refunded back to the patient.

**Naming note:** the codebase already uses "prepaid" for a different, pre-existing concept — `AppointmentForm.prepaidCorrection` / `AppointmentService`'s local `prepaidBase` variable mean "the amount already paid on *this appointment* before the current edit." To avoid confusion, this feature is modeled and coded as **Wallet** (`PatientWallet`, `WalletService`, `WalletTransaction`) at every code-level identifier. "Prepaid Balance" is used only as the **user-facing label** in the UI, matching how the feature was described by the clinic owner.

---

## 2. Goals

- A patient has exactly one wallet with a running `balance` (₹, never negative).
- Staff can **top up** a patient's wallet at any time, from any page where a patient is already in context (patient detail, appointment form, appointment detail) — not just a dedicated wallet page.
- At appointment billing time, staff can apply funds from the wallet toward `grandTotal`, alongside the existing cash/UPI/card payment fields. If the wallet balance is less than `grandTotal`, the difference is paid the normal way.
- Staff can **refund** unused wallet balance back to the patient (cash out).
- Every top-up, usage, reversal, and refund is recorded in an auditable per-patient ledger, visible on the patient detail page.
- Revenue is recognized only when a wallet amount is actually **used against an appointment**, never at top-up time — top-up is a liability, not revenue (Decided in §11).
- If money already applied to an appointment stops being needed there — the appointment's total drops (discount, line removed), the appointment is cancelled/no-show, or staff explicitly reduces the wallet-applied amount on edit — the excess is automatically credited back to the wallet.

### Non-goals (explicitly out of scope for this iteration)

- Expiry / time-limited balances — wallet balance never expires (Decided, §11).
- Promotional top-up bonuses (e.g. "pay 5,000, get 5,500 credit") — plain 1:1 top-up only.
- Splitting a single top-up or refund across multiple payment methods — one `paymentMethod` per transaction.
- Multi-currency or negative/overdraft balances — balance is always `>= 0`; shortfalls are always paid at appointment time, never borrowed against the wallet.
- A dedicated "wallet reports" page — this doc adds wallet figures to the existing dashboard/report surfaces (§8) but does not define new report pages.
- Implementation itself — this is a requirements document only.

---

## 3. Domain Model Changes

### 3.1 New entity: `PatientWallet`

```java
@Entity
@Table(name = "patient_wallet")
public class PatientWallet {
    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    private Long version;   // optimistic lock — guards concurrent top-up/usage on the same wallet

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- Created lazily on first use (first top-up, or first time an appointment tries to show "available balance") rather than seeded for every patient — most patients will never use this feature.
- `@Version` protects against two staff members applying/topping-up the same patient's wallet at the same moment (rare on a single-clinic admin tool, but cheap to add).

### 3.2 New entity: `WalletTransaction`

```java
@Entity
@Table(name = "wallet_transaction")
public class WalletTransaction {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletTransactionType type;   // TOP_UP, USAGE, REVERSAL, REFUND

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;   // always positive; type + balance delta direction below implies sign

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;   // set for TOP_UP and REFUND only; null for USAGE/REVERSAL

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;   // set for USAGE/REVERSAL only; null for TOP_UP/REFUND

    @Column(length = 255)
    private String note;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

```java
public enum WalletTransactionType {
    TOP_UP,     // balance += amount, cash/UPI/etc. actually received — a reconciliation event
    USAGE,      // balance -= amount, applied to an appointment's amountPaid
    REVERSAL,   // balance += amount, a previously-applied USAGE unwound because the appointment's owed amount dropped
    REFUND      // balance -= amount, cash/UPI/etc. actually paid back out to the patient — a reconciliation event
}
```

- `TOP_UP` and `REFUND` both carry a `paymentMethod` because real money physically moves at that moment — needed for daily cash-reconciliation, same reason `Appointment.paymentMethod` exists today.
- `USAGE` and `REVERSAL` never carry a `paymentMethod` — no money physically moves, it's an internal transfer between "wallet balance" and "amount owed on appointment X."

### 3.3 `Appointment` — one new field

```java
@Column(precision = 12, scale = 2)
private BigDecimal walletAmountApplied = BigDecimal.ZERO;
```

- Tracks the appointment's *current* wallet-sourced portion of `amountPaid` — mirrors how `discountAmount` is stored as a resolved value on the appointment (see core doc's Discounts business rule). Needed so edits/cancellations know how much to reverse (§5.4) without re-summing the ledger.
- `amountPaid = walletAmountApplied + <cash/UPI/card/etc. amount>` at all times.

### 3.4 ER Diagram Update

Add to the Mermaid diagram in `Healing_House_Clinic_Requirements_v1.md` §5:

```
PATIENT ||--o| PATIENT_WALLET : "has"
PATIENT ||--o{ WALLET_TRANSACTION : "history"
APPOINTMENT ||--o{ WALLET_TRANSACTION : "USAGE/REVERSAL source"
```

### 3.5 Rollout

`hibernate.ddl-auto: update` auto-creates `patient_wallet` and `wallet_transaction`, and adds `wallet_amount_applied` (default `0.00`) to `appointment` — no backfill needed since the feature has no historical data to reconcile (every existing appointment's wallet-applied amount is correctly `0`).

---

## 4. DTO Changes

### 4.1 New: `WalletTopUpForm`

```java
private Long patientId;
private BigDecimal amount;        // required, > 0
private PaymentMethod paymentMethod;  // required
private String note;              // optional
```

### 4.2 New: `WalletRefundForm`

```java
private Long patientId;
private BigDecimal amount;        // required, > 0, validated <= current balance
private PaymentMethod paymentMethod;  // required — how the refund was paid out
private String note;              // optional
```

### 4.3 `AppointmentForm` — new field

```java
private BigDecimal walletAmountApplied;   // nullable; how much of grandTotal to draw from the wallet
```

Same "new payment this submission" vs. "already-applied base" split that `amountPaid`/`prepaidCorrection` already use for cash payments (§5.4 below) applies to wallet amounts too on edit.

### 4.4 `AppointmentForm.from(Appointment appt)`

Copy `appt.getWalletAmountApplied()` into the edit-mode form so the field is pre-populated with whatever is currently applied.

---

## 5. Business Rules

### 5.1 Top-up

- Any amount `> 0`, any `PaymentMethod`. Creates a `TOP_UP` transaction and increments `PatientWallet.balance` (creating the wallet row on first use).
- Available from a reusable modal/component embeddable on the patient detail page, the appointment form, and the appointment detail page — staff should never need to leave an in-progress screen to top up a patient's balance.

### 5.2 Applying wallet balance to an appointment

- On the appointment form, once `grandTotal` is resolved (post-discount), show the patient's current balance and a "Apply from balance" input, capped client-side and server-side at `min(currentBalance, grandTotal)`.
- `amountPaid` = `walletAmountApplied` + the existing cash/UPI/card amount fields. The existing "amount paid can never exceed grandTotal" guard (`AppointmentService.createAppointment`/`updateAppointment`) now validates against this combined sum.
- On save, `WalletService.applyToAppointment(patientId, appointmentId, amount)` creates a `USAGE` transaction and decrements balance, in the same `@Transactional` boundary as the appointment save — a failed appointment save must not leave a dangling wallet debit.
- If a patient has no wallet yet (never topped up), available balance is simply `0` — the "apply from balance" UI can stay hidden or disabled rather than erroring.

### 5.3 Revenue recognition

- Top-up does **not** count as revenue anywhere (`DashboardService` KPIs/trend, all reports) — it is a liability (money owed to the patient in services, not yet earned). Confirmed by clinic owner (Decided, §11).
- Revenue continues to be recognized exactly as it is today: off `Appointment.grandTotal` at the point the appointment happens, regardless of whether it was paid by wallet, cash, UPI, card, or a mix. Wallet is simply another *payment source*, not a revenue event.
- Cash-reconciliation reporting (a different concern from revenue) does need TOP_UP and REFUND transactions, since real money changes hands then — see §8.

### 5.4 Editing / cancelling appointments — auto-reversal

- General rule: `walletAmountApplied` on an appointment must never exceed `min(current grandTotal, amount actually needed)`. Whenever an edit causes that ceiling to drop below the currently-applied amount, the difference is reversed back to the wallet automatically:
  - Discount added/increased, or a line removed/reduced, lowering `grandTotal` below the current `walletAmountApplied` + other payments.
  - Appointment status changes to `CANCELLED` or `NO_SHOW` — patient didn't receive (the remainder of) the service, so wallet-sourced payment on it is fully reversed.
  - Staff explicitly lowers the "apply from balance" amount on an edit.
- Each reversal creates a `REVERSAL` `WalletTransaction` linked to the appointment, and credits `PatientWallet.balance`. This is symmetric with `applyToAppointment` — `WalletService.reverseForAppointment(appointmentId, amount)`.
- This reuses the same edit-time recalculation point where `AppointmentService.updateAppointment` already recomputes `grandTotal` post-discount and re-validates `amountPaid` — the wallet reconciliation happens right after `grandTotal` is finalized, before the final `amountPaid` guard runs.
- Increasing `walletAmountApplied` on an edit (staff wants to draw more from the wallet than before) is a normal `USAGE` transaction for the incremental difference, capped by the same `min(balance, grandTotal)` rule as a new appointment.

### 5.5 Refunds

- Staff can refund any amount `<= PatientWallet.balance` back to the patient, recording how it was paid out (`PaymentMethod`). Creates a `REFUND` transaction and decrements balance.
- No connection to any specific appointment — a refund reduces the general wallet balance, not a specific prior top-up.

### 5.6 Balance invariant

- `PatientWallet.balance` must never go negative. Every debit path (`applyToAppointment`, `refund`) validates `amount <= balance` before decrementing, inside the same transaction, guarded additionally by the `@Version` optimistic lock (§3.1) against concurrent debits racing past each other.

---

## 6. Service / Controller Changes

### 6.1 `WalletService` (new)

- `getOrCreateWallet(patientId)`
- `topUp(patientId, amount, paymentMethod, note)` → `TOP_UP`
- `refund(patientId, amount, paymentMethod, note)` → `REFUND`, validated against balance
- `applyToAppointment(patientId, appointmentId, amount)` → `USAGE`, validated against balance
- `reverseForAppointment(patientId, appointmentId, amount)` → `REVERSAL`
- `getTransactionHistory(patientId, pageable)` — feeds the patient detail wallet card

### 6.2 `WalletController` (new)

- `POST /patients/{id}/wallet/topup` — `WalletTopUpForm`, redirects back to the referring page (patient detail, or back to the in-progress appointment form via a redirect param) with a flash message.
- `POST /patients/{id}/wallet/refund` — `WalletRefundForm`, same pattern.
- `GET /patients/{id}/wallet` — fragment/partial for embedding the balance + history, reused by patient detail and (via AJAX) the appointment form's balance display.

### 6.3 `AppointmentService` integration

- `createAppointment`: after `grandTotal` is finalized (post-discount) and before the `amountPaid > grandTotal` guard, call `WalletService.applyToAppointment` for `form.getWalletAmountApplied()` if present, then include it in the `amountPaid` sum.
- `updateAppointment`: same, plus the reversal logic from §5.4 when the previously-applied amount exceeds the new ceiling.
- Status transitions to `CANCELLED`/`NO_SHOW` (wherever that's currently handled — appointment status update path) must trigger `reverseForAppointment` for the full `walletAmountApplied` if not already zero.

---

## 7. UI / Template Changes

### 7.1 `templates/patients/detail.html`

- New "Prepaid Balance" card: current balance, "Top Up" and "Refund" buttons (the latter disabled/hidden when balance is `0`), and a paginated transaction history table (type, amount, method, linked appointment if any, date, note) — same pattern as the existing appointment history section on this page.

### 7.2 Reusable Top Up modal/component

- A Bootstrap modal (amount, payment method, optional note) embeddable via a Thymeleaf fragment, included on:
  - `templates/patients/detail.html`
  - `templates/appointments/form.html` (so staff can top up mid-booking if the patient's balance is insufficient)
  - `templates/appointments/detail.html`
- Submits via the `POST /patients/{id}/wallet/topup` endpoint (plain form submit + redirect back is sufficient for v1; AJAX refresh is a nice-to-have, not required).

### 7.3 `templates/appointments/form.html`

- Once a patient is selected and `grandTotal` is computed, show "Available balance: ₹X" with an "Apply from balance" input, capped at `min(balance, grandTotal)` — same live-validation pattern as the existing `checkPaymentExceedsTotal`/`clearZeroOnFocus` JS for the amount-paid field.
- A "Top Up" link/button next to the balance display opens the modal from §7.2 without losing in-progress form state (open in a new tab, or preserve form state via the browser — implementer's judgment; losing entered appointment data to top up mid-booking would be a poor UX).

### 7.4 `templates/appointments/detail.html`

- Payment breakdown section shows wallet amount applied as a distinct line (e.g. "Paid from wallet: ₹X", "Paid via {method}: ₹Y"), alongside the existing discount badge/strikethrough display.

---

## 8. Reporting / Dashboard Impact

### 8.1 `DashboardService` — unaffected for revenue

- Revenue KPIs, trend, and `Appointment.grandTotal`-based figures are unaffected by this feature per §5.3 — a wallet-funded appointment counts as revenue exactly like a cash-funded one, at the same point in time it does today.

### 8.2 New consideration: cash reconciliation

- Daily/period reports that reconcile "money physically received today" (if any exist or are added later) must include `TOP_UP` transactions as money in, and `REFUND` transactions as money out — separately from appointment revenue, since a top-up is not revenue but is real cash received. This document flags the need; it does not define a new report page (non-goal, §2).

### 8.3 Optional dashboard tile (implementer's judgment, not required for v1)

- "Total outstanding wallet balance" (sum of all `PatientWallet.balance`) is useful to the owner as a liability figure — how much pre-paid service the clinic still owes patients. Not required for acceptance but flagged as a natural low-cost addition.

---

## 9. Acceptance Criteria

1. Staff can top up any patient's wallet from the patient detail page, the appointment form, or the appointment detail page, recording amount + payment method.
2. A patient's wallet balance is visible on their detail page along with a full, paginated transaction history.
3. When creating or editing an appointment, staff can apply up to `min(balance, grandTotal)` from the patient's wallet toward the bill; any shortfall is paid via the existing cash/UPI/card fields.
4. Wallet balance never goes negative — attempting to apply or refund more than the current balance is rejected server-side (and prevented client-side).
5. Reducing an appointment's owed amount below its currently-applied wallet amount (discount, line removal, cancellation, no-show, or staff manually reducing the applied amount) automatically credits the difference back to the wallet.
6. Staff can refund unused wallet balance back to a patient, recording the payout method; this decrements the balance and appears in the transaction history.
7. Wallet top-ups are never counted as revenue in the dashboard or any report; revenue continues to be recognized off `Appointment.grandTotal` exactly as before, regardless of payment source.
8. No regressions to the existing "amount paid can never exceed grandTotal" guard, discount distribution, or stock deduction logic.

---

## 10. Naming Collision Flag

`AppointmentForm.prepaidCorrection` and `AppointmentService`'s `prepaidBase` (meaning "amount already paid on this appointment before this edit") predate this feature and refer to something unrelated to the new wallet. Implementer should **not** rename the existing fields (out of scope / needless churn) but must keep all new wallet code under `Wallet*` naming to avoid conflating the two concepts in code, tests, and commit messages. UI copy may still say "Prepaid Balance" since that's the terminology the clinic owner used and patients will recognize.

---

## 11. Decided (Open Questions Resolved)

All confirmed by clinic owner, July 12, 2026:

- **Revenue recognition:** Top-up is not revenue; revenue is recognized only when wallet funds are consumed against an appointment (§5.3).
- **Top-up payment method:** Captured per top-up, separate from appointment payment method, for cash reconciliation (§3.2, §5.1).
- **Negative balance:** Never allowed — shortfalls are always paid at appointment time (§5.6).
- **Refunds:** Supported — staff can cash out unused balance back to the patient (§5.5).
- **Expiry:** None — wallet balance is indefinite (§2 non-goals).
- **Top-up entry points:** Any page where a patient is in context, not just a dedicated wallet page (§5.1, §7.2).
- **Editing/cancelling appointments:** Any drop in an appointment's wallet-funded amount auto-reverses the difference back to the wallet (§5.4).
- **Scope of this document:** Requirements only — implementation is a separate follow-up task once this document is reviewed.

---

*Document Version 1.0 — Healing House Clinic — July 2026*
