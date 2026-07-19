package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.RolePermissionRepository;
import com.clinic.healinghouse.repository.UserRepository;
import com.clinic.healinghouse.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.clinic.healinghouse.entity.AppRole.*;
import static com.clinic.healinghouse.entity.Module.*;
import static com.clinic.healinghouse.entity.PermissionAction.*;

/**
 * One-time bootstrap of the first login account and the default Access Control Matrix
 * (requirements/Security_RBAC_Requirements_v1.md §7, §9, §12 Phase B): the app ships with zero
 * users and zero RolePermission rows, so without this there would be no way to log in, and every
 * @RequiresPermission check would deny everyone once Phase B's enforcement went live.
 * Always-on (not @Profile-gated like DataSeeder) — test/preprod/prod all need this seed exactly
 * like dev does, mirroring OwnerFlagBackfill's always-on, idempotent one-time-fixup pattern.
 * Deliberately fails startup with a clear message rather than seeding a guessable default password
 * — HEALING_HOUSE_OWNER_PASSWORD is required in every profile, dev included (§11 decision, §7 dev note).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecuritySeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final HealingHouseProperties properties;
    private final PermissionService permissionService;

    @Override
    @Transactional
    public void run(String... args) {
        seedOwnerAccount();
        seedRolePermissions();
        backfillPackageTemplateApprovePermission();
        backfillTherapistPatientAccess();
        backfillTherapistAppointmentCreate();
        backfillTherapistWalletAndPackageCreate();
        backfillTherapistWalletAndPackageApprove();
        revokeReportsForNonAdminRoles();
        backfillFullAccessMatrix();
        // PermissionService's own @PostConstruct cache load already ran (and found an empty table)
        // before this CommandLineRunner executes — Spring always finishes all @PostConstruct calls
        // before invoking any CommandLineRunner, regardless of runner order. Without this, a
        // first-ever boot against an empty DB seeds the rows correctly but denies every permission
        // check (including the OWNER's own login landing on "/") until something else calls reload().
        permissionService.reload();
    }

    private void seedOwnerAccount() {
        if (userRepository.existsByRole(AppRole.OWNER)) {
            return;
        }

        String ownerPassword = properties.getSecurity().getOwnerPassword();
        if (ownerPassword == null || ownerPassword.isBlank()) {
            throw new IllegalStateException(
                    "No OWNER login exists yet and the HEALING_HOUSE_OWNER_PASSWORD env var is not set. "
                            + "Set it and restart the application to seed the initial owner account.");
        }

        String ownerUsername = properties.getSecurity().getOwnerUsername();
        User owner = User.builder()
                .username(ownerUsername)
                .passwordHash(passwordEncoder.encode(ownerPassword))
                .fullName(properties.getOwner().getFullName())
                .role(AppRole.OWNER)
                .active(true)
                .mustChangePassword(true)
                .build();
        userRepository.save(owner);
        log.info("Seeded initial OWNER login account username='{}' — password must be changed on first login.",
                ownerUsername);
    }

    /**
     * Seeds the §4 Access Control Matrix defaults. Only rows with an actual enforcement point in
     * the app are seeded — e.g. no DELETE row for APPOINTMENTS (no delete endpoint exists, only
     * cancel/no-show status changes via APPROVE) and no EDIT/DELETE row for PATIENT_PACKAGES (no
     * edit endpoint exists). One deliberate narrowing versus the matrix's literal text: RECEPTIONIST
     * gets no APPROVE on PATIENT_PACKAGES (i.e. can sell but not refund), mirroring the identical,
     * unambiguous asymmetry the matrix already spells out for WALLET (receptionist tops up but
     * can't refund) — refunds reverse money and stay Owner/Admin/RECEPTIONIST-restricted, but
     * THERAPIST is granted APPROVE on both WALLET and PATIENT_PACKAGES (see the THERAPIST block
     * below) since therapists at this clinic handle the full payment-correction workflow, not
     * just RECEPTIONIST.
     */
    private void seedRolePermissions() {
        if (rolePermissionRepository.count() > 0) {
            return;
        }

        List<RolePermission> defaults = new ArrayList<>();

        // ── OWNER — full operational access + sole Access Matrix editor ──
        grant(defaults, OWNER, DASHBOARD, VIEW);
        grant(defaults, OWNER, PATIENTS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, OWNER, APPOINTMENTS, VIEW, CREATE, EDIT, APPROVE);
        grant(defaults, OWNER, THERAPISTS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, OWNER, SERVICES, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, OWNER, PRODUCTS, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, OWNER, COMBOS, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, OWNER, PACKAGE_TEMPLATES, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, OWNER, TAGS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, OWNER, PATIENT_PACKAGES, VIEW, CREATE, APPROVE);
        grant(defaults, OWNER, WALLET, VIEW, CREATE, APPROVE);
        grant(defaults, OWNER, REPORTS_STANDARD, VIEW, EXPORT);
        grant(defaults, OWNER, REPORTS_REVENUE, VIEW, EXPORT);
        grant(defaults, OWNER, USER_MANAGEMENT, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, OWNER, ACCESS_MATRIX, VIEW, EDIT);

        // ── ADMIN — same operational access as OWNER; Access Matrix is read-only ──
        // (ADMIN can't act on OWNER-role User accounts — enforced in the Phase D user-management
        // service, not this coarse module/action gate.)
        grant(defaults, ADMIN, DASHBOARD, VIEW);
        grant(defaults, ADMIN, PATIENTS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, APPOINTMENTS, VIEW, CREATE, EDIT, APPROVE);
        grant(defaults, ADMIN, THERAPISTS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, SERVICES, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, ADMIN, PRODUCTS, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, ADMIN, COMBOS, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, ADMIN, PACKAGE_TEMPLATES, VIEW, CREATE, EDIT, DELETE, APPROVE);
        grant(defaults, ADMIN, TAGS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, PATIENT_PACKAGES, VIEW, CREATE, APPROVE);
        grant(defaults, ADMIN, WALLET, VIEW, CREATE, APPROVE);
        grant(defaults, ADMIN, REPORTS_STANDARD, VIEW, EXPORT);
        grant(defaults, ADMIN, REPORTS_REVENUE, VIEW, EXPORT);
        grant(defaults, ADMIN, USER_MANAGEMENT, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, ACCESS_MATRIX, VIEW);

        // ── RECEPTIONIST — front desk: bookings, patients, payments; no catalog/master-data edit,
        // no commission/revenue ₹ visibility (enforced at template level, not this module gate);
        // no Reports access at all (clinic-wide ₹/commission data is Owner/Admin-only) ──
        grant(defaults, RECEPTIONIST, DASHBOARD, VIEW);
        grant(defaults, RECEPTIONIST, PATIENTS, VIEW, CREATE, EDIT);
        grant(defaults, RECEPTIONIST, APPOINTMENTS, VIEW, CREATE, EDIT, APPROVE);
        grant(defaults, RECEPTIONIST, THERAPISTS, VIEW);
        grant(defaults, RECEPTIONIST, SERVICES, VIEW);
        grant(defaults, RECEPTIONIST, PRODUCTS, VIEW);
        grant(defaults, RECEPTIONIST, COMBOS, VIEW);
        grant(defaults, RECEPTIONIST, PACKAGE_TEMPLATES, VIEW);
        grant(defaults, RECEPTIONIST, TAGS, VIEW);
        grant(defaults, RECEPTIONIST, PATIENT_PACKAGES, VIEW, CREATE);
        grant(defaults, RECEPTIONIST, WALLET, VIEW, CREATE);

        // ── THERAPIST — own schedule/earnings only (row-level "own" scoping is Phase C); can
        // create/edit patients and create appointments, can edit only appointments they're
        // involved in; view-only on master data (Services/Products/Combos/Package Templates —
        // buttons hidden client-side too, not just denied server-side); no Tags, no reports at
        // all, no user/matrix admin. Can top up a patient's wallet, sell packages, AND refund
        // either (CREATE + APPROVE) — same full access as RECEPTIONIST plus refund rights, since
        // this clinic has therapists routinely handling front-desk-style patient payment
        // corrections, not just OWNER/ADMIN ──
        grant(defaults, THERAPIST, DASHBOARD, VIEW);
        grant(defaults, THERAPIST, PATIENTS, VIEW, CREATE, EDIT);
        grant(defaults, THERAPIST, APPOINTMENTS, VIEW, CREATE, EDIT, APPROVE);
        grant(defaults, THERAPIST, THERAPISTS, VIEW);
        grant(defaults, THERAPIST, SERVICES, VIEW);
        grant(defaults, THERAPIST, PRODUCTS, VIEW);
        grant(defaults, THERAPIST, COMBOS, VIEW);
        grant(defaults, THERAPIST, PACKAGE_TEMPLATES, VIEW);
        grant(defaults, THERAPIST, PATIENT_PACKAGES, VIEW, CREATE, APPROVE);
        grant(defaults, THERAPIST, WALLET, VIEW, CREATE, APPROVE);

        rolePermissionRepository.saveAll(defaults);
        log.info("Seeded {} default role-permission rows.", defaults.size());
    }

    /**
     * One-time idempotent fix-up (mirrors OwnerFlagBackfill's pattern) for databases that already
     * had RolePermission rows seeded before package-template permanent-delete existed — since
     * seedRolePermissions() short-circuits on a non-empty table, those installs would otherwise
     * never get the new PACKAGE_TEMPLATES/APPROVE row and the permanent-delete button would 403
     * even for OWNER/ADMIN. Grants it exactly where COMBOS/APPROVE is already granted above.
     */
    private void backfillPackageTemplateApprovePermission() {
        List<RolePermission> toAdd = new ArrayList<>();
        for (AppRole role : new AppRole[]{OWNER, ADMIN}) {
            if (!rolePermissionRepository.existsByRoleAndModuleAndAction(role, PACKAGE_TEMPLATES, APPROVE)) {
                toAdd.add(RolePermission.builder().role(role).module(PACKAGE_TEMPLATES).action(APPROVE).granted(true).build());
            }
        }
        if (!toAdd.isEmpty()) {
            rolePermissionRepository.saveAll(toAdd);
            log.info("Backfilled {} PACKAGE_TEMPLATES/APPROVE role-permission row(s).", toAdd.size());
        }
    }

    /**
     * One-time idempotent fix-up for databases seeded before THERAPIST gained patient
     * create/edit rights — mirrors {@link #backfillPackageTemplateApprovePermission()}.
     */
    private void backfillTherapistPatientAccess() {
        List<RolePermission> toAdd = new ArrayList<>();
        for (PermissionAction action : new PermissionAction[]{CREATE, EDIT}) {
            if (!rolePermissionRepository.existsByRoleAndModuleAndAction(THERAPIST, PATIENTS, action)) {
                toAdd.add(RolePermission.builder().role(THERAPIST).module(PATIENTS).action(action).granted(true).build());
            }
        }
        if (!toAdd.isEmpty()) {
            rolePermissionRepository.saveAll(toAdd);
            log.info("Backfilled {} THERAPIST/PATIENTS role-permission row(s).", toAdd.size());
        }
    }

    /**
     * One-time idempotent fix-up for databases seeded before THERAPIST gained appointment
     * create rights — mirrors {@link #backfillPackageTemplateApprovePermission()}.
     */
    private void backfillTherapistAppointmentCreate() {
        if (!rolePermissionRepository.existsByRoleAndModuleAndAction(THERAPIST, APPOINTMENTS, CREATE)) {
            rolePermissionRepository.save(
                    RolePermission.builder().role(THERAPIST).module(APPOINTMENTS).action(CREATE).granted(true).build());
            log.info("Backfilled THERAPIST/APPOINTMENTS/CREATE role-permission row.");
        }
    }

    /**
     * One-time idempotent fix-up for databases seeded before THERAPIST gained wallet top-up and
     * package-sale rights — mirrors {@link #backfillTherapistPatientAccess()}. Finds-or-creates
     * rather than checking existence alone, because {@link #backfillFullAccessMatrix()} may have
     * already inserted these exact (role, module, action) cells as granted=false on a prior boot
     * (any boot between when that method shipped and this one), before this feature existed —
     * a plain existence check would treat that row as "already handled" and never flip it on.
     */
    private void backfillTherapistWalletAndPackageCreate() {
        List<RolePermission> toSave = new ArrayList<>();
        for (Module module : new Module[]{WALLET, PATIENT_PACKAGES}) {
            RolePermission rp = rolePermissionRepository.findByRoleAndModuleAndAction(THERAPIST, module, CREATE)
                    .orElseGet(() -> RolePermission.builder().role(THERAPIST).module(module).action(CREATE).granted(false).build());
            if (!rp.isGranted()) {
                rp.setGranted(true);
                toSave.add(rp);
            }
        }
        if (!toSave.isEmpty()) {
            rolePermissionRepository.saveAll(toSave);
            log.info("Backfilled {} THERAPIST WALLET/PATIENT_PACKAGES CREATE role-permission row(s).", toSave.size());
        }
    }

    /**
     * One-time idempotent fix-up for databases seeded before THERAPIST gained wallet/package
     * refund rights — mirrors {@link #backfillTherapistWalletAndPackageCreate()}, same
     * find-or-create reasoning (backfillFullAccessMatrix may have already inserted these cells
     * as granted=false before this feature existed).
     */
    private void backfillTherapistWalletAndPackageApprove() {
        List<RolePermission> toSave = new ArrayList<>();
        for (Module module : new Module[]{WALLET, PATIENT_PACKAGES}) {
            RolePermission rp = rolePermissionRepository.findByRoleAndModuleAndAction(THERAPIST, module, APPROVE)
                    .orElseGet(() -> RolePermission.builder().role(THERAPIST).module(module).action(APPROVE).granted(false).build());
            if (!rp.isGranted()) {
                rp.setGranted(true);
                toSave.add(rp);
            }
        }
        if (!toSave.isEmpty()) {
            rolePermissionRepository.saveAll(toSave);
            log.info("Backfilled {} THERAPIST WALLET/PATIENT_PACKAGES APPROVE role-permission row(s).", toSave.size());
        }
    }

    /**
     * One-time idempotent fix-up for databases seeded before Reports access was pulled from
     * RECEPTIONIST/THERAPIST — revokes rather than deletes, so the row (and its Access Matrix
     * checkbox) stays available for the Owner to re-grant later if desired.
     */
    private void revokeReportsForNonAdminRoles() {
        List<RolePermission> toRevoke = new ArrayList<>();
        for (AppRole role : new AppRole[]{RECEPTIONIST, THERAPIST}) {
            for (PermissionAction action : new PermissionAction[]{VIEW, EXPORT}) {
                rolePermissionRepository.findByRoleAndModuleAndAction(role, REPORTS_STANDARD, action)
                        .filter(RolePermission::isGranted)
                        .ifPresent(toRevoke::add);
            }
        }
        if (!toRevoke.isEmpty()) {
            toRevoke.forEach(rp -> rp.setGranted(false));
            rolePermissionRepository.saveAll(toRevoke);
            log.info("Revoked {} REPORTS_STANDARD role-permission row(s) from RECEPTIONIST/THERAPIST.", toRevoke.size());
        }
    }

    /**
     * Ensures every (role, module, action) triple has a RolePermission row — the Access Matrix
     * UI (requirements/Security_RBAC_Requirements_v1.md §8.2) only renders a checkbox for cells
     * that already have a row, so any combination the original seed didn't anticipate was
     * permanently stuck showing "—" with no way for the Owner to turn it on. Inserts missing
     * cells as granted=false (a no-op until explicitly toggled); already-existing rows are left
     * untouched. Runs last, after the role-specific backfills above, so it never races them.
     */
    private void backfillFullAccessMatrix() {
        List<RolePermission> toAdd = new ArrayList<>();
        for (AppRole role : AppRole.values()) {
            for (Module module : Module.values()) {
                for (PermissionAction action : PermissionAction.values()) {
                    if (!rolePermissionRepository.existsByRoleAndModuleAndAction(role, module, action)) {
                        toAdd.add(RolePermission.builder().role(role).module(module).action(action).granted(false).build());
                    }
                }
            }
        }
        if (!toAdd.isEmpty()) {
            rolePermissionRepository.saveAll(toAdd);
            log.info("Backfilled {} missing role-permission row(s) so the Access Matrix covers every cell.", toAdd.size());
        }
    }

    private void grant(List<RolePermission> list, AppRole role, Module module, PermissionAction... actions) {
        for (PermissionAction action : actions) {
            list.add(RolePermission.builder().role(role).module(module).action(action).granted(true).build());
        }
    }
}