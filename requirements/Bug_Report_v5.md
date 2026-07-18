# Bug Report — Full Application Review (v5)

**Date:** 2026-07-19
**Method:** Static code review of the full application, with primary focus on the authentication/authorization (RBAC) layer added since `Bug_Report_v4.md` was written — `security/*` (login, sessions, permission aspect/cache, lockout), `config/SecurityConfig.java`/`SecuritySeeder.java`, every controller's `@RequiresPermission`/inline `permissionService.require(...)` coverage, and the Phase C "own data" scoping helpers in `AppointmentController`/`TherapistController`/`ReportController`/`WalletController`/`PackageController`. Business logic outside the security layer (money math, packages, combos, discounts, stock) was spot-checked but not re-audited line-by-line, since `Bug_Report_v4.md` already covered that ground in depth and nothing in the intervening commits (`security feature done till Phase D`, `added prepaid amount flag in the appointment in the calendar`, `change flag position in calendar`, `bug ffixes`) touched it materially.
**Scope:** Builds on `Bug_Report_v1.md` through `Bug_Report_v4.md`, all of whose findings are marked FIXED as of the prior session. This pass did **not** re-verify those line-by-line; it focused on the new RBAC surface plus a couple of residual/adjacent gaps that surfaced while re-reading the code the v4 fixes touched.
**Baseline:** `mvnw compile` succeeds cleanly on the current `feature/security` branch (commit `7a9cbbb`) before any fix from this report is applied.
**Fix status (2026-07-19):** all three findings below are fixed. `mvnw test` passes (73/73) after the fixes, including the full `HealinghouseApplicationTests` context load (which exercises the new `LoginRateLimitFilter` bean wiring in `SecurityConfig` and `TherapistService`'s new `UserService` dependency).

**Overall assessment:** the RBAC implementation is unusually thorough — every controller was checked mapping-by-mapping, and the great majority of mutating/viewing endpoints correctly pair a `@RequiresPermission` (or inline `permissionService.require(...)`) with the Phase C "own therapist" scoping helpers where needed (`enforceOwnAppointmentForTherapist`, `enforceOwnTherapist`, `denyClinicWideReportsForTherapist`, `denyFullEditForTherapist`). No missing-permission-check or broken row-level THERAPIST-scoping was found on any endpoint. The three findings below are all real but narrower: an open redirect, a residual gap in a fix already applied in v4, and an offboarding/authorization gap around therapist deactivation.

---

## Summary

| # | Severity | Finding | Area | Status |
|---|----------|---------|------|--------|
| 1 | MEDIUM | Deactivating a `Therapist` does not disable or session-expire their linked `User` login — a former therapist keeps full system access | Security / Authorization | Fixed |
| 2 | MEDIUM | Open redirect via the client-supplied `returnUrl` parameter, reflected unvalidated into links and POST-redirect targets | Security / Appointments, Wallet | Fixed |
| 3 | MEDIUM (residual) | Login lockout has no IP-based throttling — an unauthenticated attacker can still re-lock any known username (e.g. the default `owner`) every time the 15-minute window expires, indefinitely | Security / Auth | Fixed |

---

## Findings

### 1. [MEDIUM] Deactivating a Therapist doesn't touch their linked User login
**Files:** `service/TherapistService.java::deactivate()` (lines ~60-65), `security/UserPrincipal.java::isEnabled()`/`getTherapistId()`, `security/PermissionService.java::currentTherapistId()`, `entity/User.java`

```java
public void deactivate(Long id) {
    Therapist therapist = getById(id);
    therapist.setActive(false);
    therapistRepository.save(therapist);
    log.info("Deactivated therapist id={} name='{}'", therapist.getId(), therapist.getFullName());
}
```

`Therapist.active` and `User.active` are two independent flags with no cascade between them. `UserPrincipal.isEnabled()` reads only `user.isActive()`; `UserPrincipal.getTherapistId()`/`PermissionService.currentTherapistId()` read `user.getTherapist().getId()` with no check on `therapist.isActive()`. Nothing in the codebase checks the linked Therapist's `active` flag as part of authentication or per-request authorization (verified via a full-codebase search for `getTherapist().isActive()` / equivalent — no matches).

**Scenario:** A therapist leaves the clinic. Staff use the obvious, documented offboarding action — the "Deactivate" button on `/therapists/{id}` (`TherapistController.delete` → `TherapistService.deactivate`) — which correctly hides them from new-appointment therapist pickers and the active roster. Their linked login account is untouched: `User.active` stays `true`, no session is invalidated (contrast with `UserService.disable()`, which does call `invalidateSessionsForUser`). The former therapist can still log in — or, if already logged in, their session never expires early — and continues to see "their own" appointments/earnings/calendar/patients via every Phase C scoping check in `AppointmentController`/`TherapistController`/`ReportController`, since those all key off `currentTherapistId()`, which is oblivious to the deactivation. The only way to actually revoke access is a second, separate, easy-to-forget step: `/admin/users` → disable the linked user account.

**Fix direction:** either (a) have `TherapistService.deactivate` look up and disable the linked `User` (mirroring `UserService.disable`'s session invalidation), or (b) fold an active-therapist check into `UserPrincipal.isEnabled()` for THERAPIST-role users. (a) is more consistent with the rest of the app's soft-delete pattern (explicit, staff-visible state) and avoids silently locking out a user who might later be intentionally re-linked.

**Fix applied:** (a). `UserService.disableLinkedToTherapist(Long therapistId)` looks up the linked `User` via the existing `UserRepository.findByTherapistId`, sets `active = false`, and calls the same `invalidateSessionsForUser` used by `disable()` — a no-op if there's no linked user or it's already inactive. `TherapistService.deactivate()` now calls it right after flipping `Therapist.active = false`. `activate()` was deliberately left untouched — reactivating a therapist doesn't auto-restore their login, since that's a separate, explicit admin decision (`/admin/users` → enable).

---

### 2. [MEDIUM] Open redirect via the `returnUrl` parameter
**Files:** `controller/AppointmentController.java` (`detail` line 220, `complete`/`cancel`/`noShow` lines 328-372, `reassignServiceLineTherapist`/`reassignProductLineTherapist` lines 379-423), `controller/WalletController.java::returnUrlOrDefault` (lines 79-81), `templates/appointments/detail.html` (lines 32, 39, 89, 283, 312, 378, 458)

```java
// AppointmentController
@PostMapping("/{id}/cancel")
public String cancel(@PathVariable Long id,
                     @RequestParam(defaultValue = "") String cancelReason,
                     @RequestParam(defaultValue = "") String returnUrl,
                     RedirectAttributes ra) {
    ...
    return "redirect:" + (returnUrl.isBlank() ? "/appointments/" + id : returnUrl);   // unvalidated
}

// WalletController
private String returnUrlOrDefault(String returnUrl, Long patientId) {
    return (returnUrl == null || returnUrl.isBlank()) ? "/patients/" + patientId : returnUrl;  // unvalidated
}
```

`returnUrl` arrives as a raw query parameter on `GET /appointments/{id}` and is echoed straight into the model, then into `appointments/detail.html`'s hidden `<input name="returnUrl" th:value="${returnUrl}">` fields (inside the complete/cancel/no-show/reassign forms) and directly into a link's `th:href="${returnUrl}"` (the "Back" button). None of `AppointmentController`'s or `WalletController`'s handlers check that the value is a same-origin/relative path before either (a) using it as a `redirect:` target after a real state-changing POST, or (b) rendering it as a clickable link's `href`.

This is the same vulnerability class as `Bug_Report_v4.md` finding #18 (open redirect via the `Referer` header, fixed in `GlobalExceptionHandler.fallbackUrl`), but via a completely separate code path that fix didn't touch.

**Scenario (simplest, zero-click-elsewhere):** An attacker sends a logged-in staff member a link:
`https://<clinic-host>/appointments/5?returnUrl=https://evil.example/login`
The page renders normally (legitimate domain, real appointment data), building trust. The "Back" link on that page has `href="https://evil.example/login"` verbatim. One click sends the victim to an attacker-controlled page that can impersonate the app's login screen to harvest credentials, or serve malware — a classic open-redirect phishing pivot, and it costs the attacker nothing to construct.

**Scenario (via a real action):** Same crafted link, but the victim instead clicks "Cancel Appointment" — a real, wanted action. The hidden `returnUrl` field (carrying the attacker's URL, copied from the query string) rides along in the legitimately-CSRF-tokened POST. The cancellation genuinely happens, then the server's `redirect:` response sends the browser to the attacker's URL, immediately after a real state change — makes the phishing page more credible ("your cancellation is confirmed, please re-login to continue") and is harder to distinguish from normal app behavior since it follows a real, expected action.

**Fix direction:** validate `returnUrl` the same way `GlobalExceptionHandler.fallbackUrl` now does (same-origin check, or simpler: require it to start with `/` and not `//`) before ever using it in a `redirect:` prefix or `th:href`; reject/fallback to the default path otherwise. Apply the same helper to both `AppointmentController` and `WalletController` rather than duplicating the logic.

**Fix applied:** new `util/SafeRedirectUtil.sanitize(url, fallback)` — returns `url` only if it starts with `/` and not `//` or `/\` (both browser-interpreted as protocol-relative, off-app), else `fallback`. Applied at every `returnUrl` entry point: `AppointmentController.detail`/`editForm`/`update` (sanitized once, right after the parameter is read, before it reaches the model or any redirect), `complete`/`cancel`/`noShow` (sanitized inline against their existing `/appointments/{id}` default), the shared `returnUrlSuffix` helper used by both per-line reassignment endpoints, and `WalletController.returnUrlOrDefault`. An invalid/absent `returnUrl` now behaves exactly like an absent one (falls back to the default in-app path or `null`), never an attacker-supplied URL.

---

### 3. [MEDIUM, residual] Login lockout has no IP-based throttling
**Files:** `security/LoginAttemptListener.java`, `config/SecurityConfig.java` (no rate-limiting filter registered), `config/HealingHouseProperties.java::Security` (`ownerUsername = "owner"` default)

`Bug_Report_v4.md` finding #3 fixed the specific bug where an already-locked account's lock kept getting *extended* by further failed attempts (an indefinite lock). That fix is correctly in place — `onFailure` now no-ops while `lockedUntil` is still in the future. But that finding's own "Fix direction" paragraph also called for **IP-based rate limiting on `/login` independent of account state**, and that part was never implemented — there is no rate-limiting filter, CAPTCHA, or per-IP throttle anywhere in `SecurityConfig`/the filter chain.

**Scenario:** An unauthenticated attacker who knows (or guesses — the seeded default is literally `"owner"`, per `HealingHouseProperties.Security.ownerUsername`) a valid username can send 5 bad-password POSTs to `/login`, locking the account for 15 minutes (per `properties.getSecurity().getMaxFailedLoginAttempts()`/`getLockoutMinutes()`). The v4 fix stops them from *extending* that lock with more attempts during the window — but the moment it naturally expires, the exact same 5-request burst re-locks it for another 15 minutes. Repeated indefinitely (a trivial cron job / script), this keeps a known account — including the sole OWNER before a second admin exists — locked essentially permanently, denying legitimate access, with zero password knowledge and no per-IP or global throttle to slow the attacker down.

**Fix direction:** add an IP-based (or combined IP+username) rate limiter in front of `/login` — even a simple in-memory sliding-window filter (e.g. N attempts per IP per minute, independent of which username is targeted) would close this; a CAPTCHA after a few failures is a common complementary mitigation. Changing the seeded default `ownerUsername` away from the guessable `"owner"` in production deployments is a cheap, worthwhile defense-in-depth step but doesn't fix the underlying gap.

**Fix applied:** new `security/LoginRateLimitFilter` (registered in `SecurityConfig` via `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`), an in-memory per-IP sliding window over POST `/login` only — counts every attempt regardless of username or outcome, rejecting (redirect to `/login?error=rate-limit`, before the request ever reaches the authentication filter) once the window's cap is hit. Two new configurable thresholds on `HealingHouseProperties.Security`: `maxLoginAttemptsPerIp` (default 10) and `loginRateLimitWindowMinutes` (default 15, same window as account lockout). `AuthController` now renders a distinct "too many attempts" message for `error=rate-limit` instead of the generic bad-credentials one. Note: this throttles the rate of attempts from a single IP (including across different targeted usernames) but does not by itself change how quickly a natural per-account lockout cycle (5 attempts / 15 min) can be repeated — it's a defense-in-depth layer alongside the account lockout, as the original fix direction called for, not a replacement for it.

---

## Suggested priority order for fixing

1. **#1** (therapist deactivation doesn't revoke login) — straightforward fix (mirror `UserService.disable`'s pattern), closes a real offboarding gap that a real clinic will hit the first time staff turns over.
2. **#2** (open redirect via `returnUrl`) — straightforward fix (reuse/extend the same-origin check already written for `Bug_Report_v4.md` #18), meaningful phishing-vector closure.
3. **#3** (login rate limiting) — more involved (new filter/component), but the residual DoS risk is real and was already flagged as incomplete by v4's own fix-direction note.
