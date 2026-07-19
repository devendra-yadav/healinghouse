# Healing House Clinic — Security & Role-Based Access Control (RBAC)

## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 17, 2026
**Status:** Draft — open questions resolved via brainstorming, ready for review before implementation
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Introduces the app's **first authentication/authorization layer** — every existing controller/service (Patients, Therapists, Appointments, Services, Products, Combos, Package Templates, Patient Packages, Tags, Wallet, Reports) becomes permission-gated. Adds two new modules: **User Management** and **Access Matrix (Role Permissions) UI**.

---

## 1. Problem Statement

The application currently has **no authentication** — anyone with network access to the app can view and modify all clinic data (patient records, financials, therapist commission, permanent deletes, refunds). This is acceptable for early internal use but not for production use by multiple staff with different trust levels (owner vs. front-desk vs. therapists).

This document defines a login system plus a fine-grained, **admin-configurable** Role-Based Access Control model: a fixed set of roles, a matrix of (role × module × action) permissions seeded with sensible clinic defaults, and a web UI letting the Owner adjust that matrix without a code change or redeploy.

---

## 2. Goals

- Every page/action requires an authenticated user; unauthenticated requests redirect to a login page.
- Four fixed roles — **OWNER, ADMIN, RECEPTIONIST, THERAPIST** — each with a distinct default permission set (§4).
- Permissions are **data-driven** (stored in DB, not hardcoded `if (role == ...)` checks) so the Owner can tune them post-deployment via an **Access Matrix UI** without redeploying.
- A **THERAPIST** login is linked 1:1 to an existing `Therapist` master-data record, so "my schedule / my earnings / my patients" scoping is automatic and requires no per-user manual configuration.
- Sensitive actions (login attempts, permission-matrix edits, permanent deletes, refunds, discount overrides) are recorded in an audit trail.
- Existing business logic (commission rules, wallet reversal, package FIFO, combo pricing, discount math) is untouched — this is purely an access layer wrapped around existing controllers/services.

### Non-goals (explicitly out of scope for v1)

- **Per-user permission overrides** — permissions are per-role only; two users with the same role always have identical access. (Decided §11.)
- **Dynamic role creation** — the four roles are a fixed enum for v1, not admin-creatable. Adding a 5th role later is a code change (small, but a change).
- **Self-service password reset via email/SMS** — the app has no email/SMS sending capability today. Only an Owner/Admin can reset a user's password. (Decided §11.)
- **JWT/stateless/API authentication** — no mobile app or external API consumer exists yet; session-based form login is sufficient. (Decided §11.)
- **Single Sign-On / OAuth / social login** — plain username+password only.
- **Field-level redaction within a page** (e.g. hiding just the ₹ column but showing the rest of a row) beyond what's explicitly listed in §7 — permission granularity stops at (module, action), not individual fields, except where §7 explicitly calls out a scoped/redacted view.
- Implementation itself — this is a requirements document only.

---

## 3. Roles

| Role | Typical user | One-line intent |
|---|---|---|
| **OWNER** | Marcia (clinic owner) | Full access to everything, including all financials, commission data, and the Access Matrix editor itself. Exactly one conceptual owner, but modeled as a role (not hardcoded to one user row) in case ownership changes. |
| **ADMIN** | Manager / senior staff | Full day-to-day operational access — all master data, all bookings, user account management (create/edit/disable/reset-password for non-Owner users) — but cannot edit the permission matrix and cannot manage Owner accounts. |
| **RECEPTIONIST** | Front desk | Books appointments, manages patients, takes payments, sells/refunds packages, tops up/refunds wallet. No catalog/master-data edit rights, no commission or revenue ₹ visibility in reports. |
| **THERAPIST** | Service providers | Sees only their own schedule, their own appointments (as main or reassigned-line therapist), their own earnings row in reports, and can mark their own appointments Completed/No-Show. No access to other therapists' data, no master-data edit rights. |

A `User` is always exactly one role. A `THERAPIST`-role user is linked via a nullable `therapistId` FK to an existing `Therapist` row (§6.1) — that link is what "own" scoping resolves against everywhere in §7.

---

## 4. Access Control Matrix

`V`=View  `C`=Create  `E`=Edit  `D`=Delete  `A`=Approve/Confirm (status changes, permanent delete, refunds)  `X`=Export  `—`=No access

| Module | OWNER | ADMIN | RECEPTIONIST | THERAPIST |
|---|---|---|---|---|
| Dashboard | V | V | V *(ops KPIs only, no ₹ commission)* | V *(own schedule + own earnings widget only)* |
| Patients | V,C,E,D | V,C,E,D | V,C,E | V *(read-only; full list/detail access, no create/edit/deactivate)* |
| Appointments | V,C,E,D,A | V,C,E,D,A | V,C,E,A | V *(own only)*, E *(own line reassignment only)*, A *(mark own Completed/No-Show only)* |
| Therapists (master data) | V,C,E,D | V,C,E,D | V | V *(own profile, read-only)* |
| Services / Products / Combos / Package Templates (catalog) | V,C,E,D,A *(permanent delete)* | V,C,E,D,A *(permanent delete)* | V | V |
| Tags | V,C,E,D | V,C,E,D | V | — |
| Patient Packages (sell / refund) | V,C,E,A | V,C,E,A | V,C,A *(sell)* | V *(own, read-only)* |
| Wallet (top-up / refund) | V,C,A | V,C,A | V,C | V *(read-only, embedded in own appointment detail only — no dedicated Wallet page)* |
| Reports — Daily / Period / Comparison / Patients / Performance | V,X | V,X | V,X *(no revenue/commission ₹ columns)* | V,X *(own row only)* |
| Reports — Actual Revenue | V,X | V,X | — | — |
| User Management | V,C,E,D | V,C,E *(not Owner accounts)*, D *(not Owner accounts)* | — | — |
| Access Matrix (Role Permissions) editor | V,E | V *(read-only)* | — | — |

Notes:
- "Own" for THERAPIST always resolves via the `User.therapistId` link (§6.1), reusing the existing `AppointmentSpec.hasTherapistId` main-or-reassigned-line definition already used by the therapist detail page.
- Permanent delete (`D` marked `A` above) already requires the item be deactivated first and unreferenced — see the existing Soft delete / permanent delete business rule in `CLAUDE.md`; this doc doesn't change that logic, only gates who may invoke it.
- The DB model (§6.2) tracks each catalog type (Services, Products, Combos, Package Templates) as a **separate module** even though this table visually groups them into one row (they happen to share identical default permissions) — so the Access Matrix UI can later diverge them (e.g. give Receptionist Tags access) without a schema change.

---

## 5. Authentication Design

- **Mechanism:** Spring Security, session-based form login (`HttpSession`, not JWT) — matches this app's server-rendered Thymeleaf-only architecture with no external API consumer today.
- **Password storage:** `BCryptPasswordEncoder`.
- **Login page:** new `templates/auth/login.html`, extends the existing `fragments/layout.html` shell but without the nav (unauthenticated). Standard username + password form; Spring Security's default `UsernamePasswordAuthenticationFilter` at `POST /login`.
- **CSRF:** enabled (Spring Security default) — requires adding `thymeleaf-extras-springsecurity6` and threading `${_csrf}` / `sec:authorize` into existing forms.
- **Session timeout:** 30 minutes idle, configurable via `application.yml` (`server.servlet.session.timeout`).
- **Account lockout:** lock after 5 consecutive failed attempts for 15 minutes (standard default; tune later if it proves too strict/lax for a 4–6-person clinic staff).
- **Password policy:** minimum 8 characters; no forced complexity rules beyond that (small trusted staff, not a public-facing system) — Owner/Admin can tighten later if desired.
- **Password reset:** Admin/Owner-only, from the User Management screen (§8.1) — sets a temporary password; user is forced to change it on next login (`User.mustChangePassword` flag). No email involved.
- **Logout:** standard `POST /logout`, invalidates session.

---

## 6. Domain Model Changes

### 6.1 New entity: `User`

```java
@Entity
@Table(name = "app_user")
public class User {
    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppRole role;              // OWNER, ADMIN, RECEPTIONIST, THERAPIST

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "therapist_id")
    private Therapist therapist;       // set only when role == THERAPIST; null otherwise

    @Column(nullable = false)
    private boolean active = true;     // disabled accounts can't log in but aren't deleted

    @Column(nullable = false)
    private boolean mustChangePassword = false;

    private LocalDateTime lastLoginAt;
    private int failedLoginAttempts;
    private LocalDateTime lockedUntil;

    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- A `THERAPIST`-role `User` **must** have `therapist` set (validated at creation); other roles must leave it null.
- One `Therapist` master-data row can have at most one linked `User` account (business rule, enforced in service layer — not every `Therapist` needs a login, e.g. an inactive/former therapist).

### 6.2 New enums: `AppRole`, `Module`, `PermissionAction`

```java
public enum AppRole { OWNER, ADMIN, RECEPTIONIST, THERAPIST }

public enum Module {
    DASHBOARD, PATIENTS, APPOINTMENTS, THERAPISTS,
    SERVICES, PRODUCTS, COMBOS, PACKAGE_TEMPLATES, PATIENT_PACKAGES,
    TAGS, WALLET,
    REPORTS_STANDARD, REPORTS_REVENUE,
    USER_MANAGEMENT, ACCESS_MATRIX
}

public enum PermissionAction { VIEW, CREATE, EDIT, DELETE, APPROVE, EXPORT }
```

### 6.3 New entity: `RolePermission`

```java
@Entity
@Table(name = "role_permission",
       uniqueConstraints = @UniqueConstraint(columnNames = {"role", "module", "action"}))
public class RolePermission {
    @Id @GeneratedValue
    private Long id;

    @Enumerated(EnumType.STRING)
    private AppRole role;

    @Enumerated(EnumType.STRING)
    private Module module;

    @Enumerated(EnumType.STRING)
    private PermissionAction action;

    @Column(nullable = false)
    private boolean granted;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- Seeded at first startup (new `SecuritySeeder`, alongside the existing `DataSeeder`, gated the same "only if table empty" way) with the defaults from §4.
- Not every (module, action) combination is meaningful (e.g. `APPROVE` on `TAGS`) — the seeder only inserts applicable rows; the Access Matrix UI (§8.2) only renders applicable columns per module.
- Read into an in-memory cache (`Map<AppRole, Set<(Module,Action)>>`) on startup and refreshed whenever the Access Matrix UI saves a change — avoids a DB round-trip on every permission check given how often it's called per request.

### 6.4 New entity: `AuditLog`

```java
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue
    private Long id;

    @CreationTimestamp
    private LocalDateTime timestamp;

    private Long userId;           // nullable — null for failed-login-unknown-username
    private String username;       // snapshot, survives user deletion
    private String eventType;      // LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT, PERMISSION_CHANGE,
                                    // PERMANENT_DELETE, REFUND, USER_CREATED, USER_DISABLED, ...
    private String module;
    private String entityType;     // e.g. "Appointment", "RolePermission"
    private String entityId;
    @Column(length = 2000)
    private String details;        // free-text summary, e.g. "role=RECEPTIONIST module=TAGS action=VIEW granted:false->true"
    private String ipAddress;
}
```

- Logged events (deliberately not every VIEW — that would be noise at clinic scale): login success/failure, logout, permission-matrix changes, user create/edit/disable/password-reset, permanent deletes (Services/Products/Combos), package refunds, wallet refunds, discount overrides on an appointment.
- Visible read-only on a new `/admin/audit-log` page, OWNER only, filterable by date range/user/event type. Not exportable in v1 (no CSV/PDF for this one — internal forensic tool, not a clinic report).

---

## 7. Authorization Enforcement Design

- **Permission check API:** a `PermissionService.has(Module module, PermissionAction action)` bean, backed by the cached `RolePermission` map (§6.3) keyed off the current authenticated user's role.
- **Controller-level gating:** a custom `@RequiresPermission(module = Module.APPOINTMENTS, action = PermissionAction.CREATE)` annotation + an AOP `@Aspect` that calls `PermissionService.has(...)` before the method runs, throwing `AccessDeniedException` (→ handled by the existing `GlobalExceptionHandler`, flash-messaged redirect, matching its current pattern) on denial. Chosen over sprinkling `@PreAuthorize` SpEL everywhere — cleaner to read, and matches the module/action vocabulary of the matrix directly rather than re-deriving it in SpEL per endpoint.
- **View-level gating:** a `@Component("perm")` bean exposed to Thymeleaf so templates can do `<a th:if="${@perm.has('APPOINTMENTS','DELETE')}" ...>` to hide buttons/links a user can't act on — avoids a user reaching a page and then bouncing off a 403.
- **Data scoping for THERAPIST** (beyond the module/action gate — row-level filtering):
  - *Appointments:* list/calendar queries add a `therapistId` filter via the existing `AppointmentSpec.hasTherapistId`, scoped to the logged-in user's linked `Therapist.id`.
  - *Patients:* full read-only list/search/detail access (same as other roles); create/edit/deactivate remain blocked at the module/action gate (VIEW only, no C/E/D).
  - *Reports:* the therapist-comparison/daily/period rows are filtered server-side to the one row matching the logged-in therapist — never returns other therapists' figures even if the request is tampered with client-side.
  - *Wallet:* no dedicated `/patients/{id}/wallet` access; the "Paid from Wallet" figure only appears embedded in the detail page of an appointment the therapist is already authorized to view.
- **New-user bootstrap:** since the app currently ships with zero users, `SecuritySeeder` also creates one default `OWNER` account on first run — mirrors how `DataSeeder`/`OwnerFlagBackfill` already do one-time idempotent setup work. Username is a fixed default (e.g. `owner`); password comes from a new required env var, `HEALING_HOUSE_OWNER_PASSWORD`, read the same way `HEALING_HOUSE_DB_PASSWORD` already is for the `test`/`preprod`/`prod` profiles (see the Database Setup table in `CLAUDE.md`) — never a hardcoded literal in source or `application*.yml`. `mustChangePassword=true` is still forced on the seeded account, so the env-var value is only ever a one-time bootstrap credential, replaced on first login.
  - **Dev profile note:** the dev/default profile already hardcodes its DB password (`StrongPass123!`) directly in `application.yml`, unlike `test`/`preprod`/`prod` which require the env var. For consistency and to avoid a dev-only code path, `HEALING_HOUSE_OWNER_PASSWORD` should be required in **every** profile, including dev — a missing env var should fail `SecuritySeeder` loudly at startup rather than silently seeding a guessable default.

---

## 8. Access Matrix UI (Admin-configurable Permissions)

### 8.1 User Management (`/admin/users`)

- List/create/edit/disable/reset-password for `User` accounts. OWNER and ADMIN both have access; ADMIN cannot act on OWNER-role accounts (create, edit, disable, or reset their password) — enforced server-side, not just hidden in the UI.
- Creating a THERAPIST-role user requires picking an existing `Therapist` record (autocomplete, reusing the existing therapist search pattern) not already linked to another user.
- Disable (not delete) is the only removal path — mirrors the existing soft-delete convention (`active=false`) used everywhere else in this codebase (Services/Products/Combos).

### 8.2 Access Matrix editor (`/admin/access-matrix`)

- OWNER-only (ADMIN gets read-only view per §4's table).
- Renders exactly the grid in §4 — rows = modules, columns = the applicable actions for that module, cells = checkboxes bound to `RolePermission.granted`, one tab/section per role (or one big matrix — visual detail left to implementation, not this requirements doc).
- Save triggers: (1) persist changed `RolePermission` rows, (2) invalidate/reload the in-memory permission cache (§6.3), (3) write one `AuditLog` `PERMISSION_CHANGE` row per changed cell (old value → new value) so a later "why can Receptionist suddenly delete patients" question is answerable.
- No confirmation modal required for toggling a permission off (reversible, low-blast-radius, unlike the existing permanent-delete confirmations) — but the page should visibly warn that changes apply immediately to all users of that role.

---

## 9. Migration / Rollout Notes

- `ddl-auto: update` (existing config) auto-creates `app_user`, `role_permission`, `audit_log` tables — no manual migration needed, consistent with how every other feature in this codebase has shipped so far.
- Until this feature ships, the app has no login; rollout must happen in one deploy that both adds Spring Security and seeds the initial OWNER account — there's no safe intermediate "half-secured" state, since an unauthenticated app with a login page bolted on but no enforced filter chain is not actually secured.
- Existing URLs/templates are unaffected in structure — this wraps existing controllers, it doesn't restructure them.
- **Deployment scripts** (`src/main/linux/bin/start_healinghouse_app.bash`) need the same treatment as the existing `HEALING_HOUSE_DB_PASSWORD` check: add a blank-check guard for `HEALING_HOUSE_OWNER_PASSWORD` (mirroring lines 24–26's `if [ "a$HEALING_HOUSE_DB_PASSWORD" == "a" ]` pattern) and pass it through as a second `-D` system property on the `java` launch line (alongside the existing `-DHEALING_HOUSE_DB_PASSWORD=$HEALING_HOUSE_DB_PASSWORD`), so `test`/`preprod`/`prod` all refuse to start without it. `stop_healinghouse_app.bash` is unaffected. Once this ships, `CLAUDE.md`'s Deployment (Linux) section should be updated to list the new required env var alongside `HEALING_HOUSE_DB_PASSWORD`.

---

## 10. Dependencies to Add

- `spring-boot-starter-security`
- `thymeleaf-extras-springsecurity6`

(No other new runtime dependency — AOP for `@RequiresPermission` uses `spring-boot-starter-aop`, also a new addition.)

---

## 11. Decisions Log (resolved during brainstorming)

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Should THERAPIST login be linked to a `Therapist` master-data record? | **Yes**, nullable `therapistId` FK on `User` | Auto-scopes "own data" everywhere with zero extra per-user config; reuses existing `AppointmentSpec.hasTherapistId`. |
| 2 | Can RECEPTIONIST see revenue/commission ₹ in reports? | **No** — operational counts/status only | Front-desk staff shouldn't see payroll-adjacent commission figures. |
| 3 | Permission granularity: per-role only, or per-role + per-user overrides? | **Per-role only** for v1 | Small staff, simpler to build/audit/reason about; revisit if the clinic grows enough to need individual exceptions. |
| 4 | Can THERAPIST mark their own appointment Completed/No-Show? | **Yes** | Therapists may close out visits without a receptionist present at all times. |
| 5 | Auth mechanism? | **Spring Security session-based form login** | Thymeleaf-only app, no API/mobile consumer today; JWT would add complexity with no current payoff. |
| 6 | Forgot-password flow? | **Admin/Owner manual reset only** | No email/SMS sending capability exists in the app today; adding one just for this is out of scope. |
| 7 | Audit logging? | **Yes** — new `AuditLog` table for auth events, permission changes, permanent deletes, refunds, discount overrides | Cheap to add now; valuable if a dispute over a refund or a permission change ever comes up. |
| 8 | Who can edit the Access Matrix itself? | **OWNER only**; ADMIN gets read-only view | Keeps the "rules of the system" under a single accountable role, distinct from day-to-day user account management which ADMIN also does. |

---

## 12. Implementation Plan (Phased Rollout)

Ordered so each phase leaves the app in a working, testable state. **Phases A and B ship together in one deploy** — per §9, a build with login but no permission enforcement is a false sense of security (any authenticated user would have full access), so it must never reach `preprod`/`prod` on its own. Phases C–F can each ship independently afterward, since by then the app is already fully gated — they only add scoping/UI/audit visibility on top of an already-secured baseline.

### Phase A — Auth Infrastructure
- Add dependencies: `spring-boot-starter-security`, `thymeleaf-extras-springsecurity6`, `spring-boot-starter-aop` (§10).
- New `User` entity + `AppRole` enum (§6.1).
- `SecurityConfig`: form login, `BCryptPasswordEncoder`, 30-min session timeout, 5-attempt/15-min lockout (§5).
- `templates/auth/login.html` (§5).
- `SecuritySeeder`: seed one `OWNER` account from `HEALING_HOUSE_OWNER_PASSWORD`, fail loudly if unset in any profile (§7, §11 decision 6).
- Update `start_healinghouse_app.bash` per §9's deployment-script note.
- **Exit check:** app refuses unauthenticated requests; logging in as the seeded Owner reaches every existing page (no permission gating active yet — expected at this stage only).

### Phase B — Permission Model + Enforcement Engine
- `Module`, `PermissionAction` enums; `RolePermission` entity (§6.2–6.3).
- `SecuritySeeder` extended to seed `RolePermission` defaults from the §4 matrix.
- `PermissionService` (cached `Map<AppRole, Set<(Module,Action)>>`), `@RequiresPermission` annotation + AOP aspect (§7).
- Wire `@RequiresPermission` onto every existing controller method per §4 — the largest mechanical chunk of this phase; go module-by-module (Patients → Appointments → Therapists → catalog → Tags → Packages → Wallet → Reports) rather than all at once, so each module can be manually spot-checked against its matrix row before moving to the next.
- `@Component("perm")` Thymeleaf bean; hide/show nav links and action buttons accordingly (§7).
- `GlobalExceptionHandler`: handle `AccessDeniedException` (flash-messaged redirect, matching its existing pattern).
- **Exit check:** log in as each of the four roles (temporary test accounts) and confirm every cell in the §4 matrix — both the "granted" and the "denied" cells — behaves as specified. This is the one phase worth a deliberate regression pass against the whole matrix, not just spot checks.

### Phase C — Data Scoping for THERAPIST
- `User.therapistId` FK wiring + the "must be set iff role==THERAPIST" validation (§6.1).
- Appointment list/calendar filtering via `AppointmentSpec.hasTherapistId` (§7).
- Reports filtering to the logged-in therapist's own row only (§7).
- Patients/Wallet restricted-context access (§7).
- **Exit check:** as a THERAPIST test account linked to Therapist X, confirm zero visibility into Therapist Y's appointments, earnings, or patients — including via direct URL manipulation (e.g. `/appointments/{id}` for someone else's appointment), not just hidden nav links.

### Phase D — User Management + Access Matrix UI
- `/admin/users` CRUD (create/edit/disable/reset-password), with the ADMIN-cannot-touch-OWNER-accounts rule enforced server-side (§8.1).
- `/admin/access-matrix` editor, OWNER-only edit / ADMIN read-only, with cache invalidation on save (§8.2).
- **Exit check:** toggling a permission off in the UI takes effect for a currently logged-in user of that role on their *next* request (no restart needed) — validates the cache-invalidation wiring, not just the DB write.

### Phase E — Audit Logging
- `AuditLog` entity (§6.4) + write calls at each sensitive-action site: login success/failure, logout, permission changes, user create/edit/disable/reset, permanent deletes, package/wallet refunds, discount overrides.
- `/admin/audit-log` read-only page, OWNER-only, filterable by date/user/event type.
- **Exit check:** perform one of each logged event type and confirm it appears with correct actor/timestamp/details.

### Phase F — Documentation & Hardening
- Update `CLAUDE.md`'s Deployment (Linux) section to list `HEALING_HOUSE_OWNER_PASSWORD` alongside `HEALING_HOUSE_DB_PASSWORD` (§9 note) — only now, once actually shipped.
- Update `CLAUDE.md`'s Architecture/Domain Model sections to document `User`/`RolePermission`/`AuditLog` and the new `security`/`config` additions, matching how every other shipped feature in this codebase is documented.
- Full regression pass: re-run the Phase B matrix check plus the Phase C isolation check together, since B and C interact (a permission grant that ignores C's scoping would leak cross-therapist data even with the matrix cell correctly "denied" at the module level).