package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.RolePermissionRepository;
import com.clinic.healinghouse.repository.UserRepository;
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

    @Override
    @Transactional
    public void run(String... args) {
        seedOwnerAccount();
        seedRolePermissions();
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
     * can't refund) — refunds reverse money and are Owner/Admin-only across both features.
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
        grant(defaults, OWNER, PACKAGE_TEMPLATES, VIEW, CREATE, EDIT, DELETE);
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
        grant(defaults, ADMIN, PACKAGE_TEMPLATES, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, TAGS, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, PATIENT_PACKAGES, VIEW, CREATE, APPROVE);
        grant(defaults, ADMIN, WALLET, VIEW, CREATE, APPROVE);
        grant(defaults, ADMIN, REPORTS_STANDARD, VIEW, EXPORT);
        grant(defaults, ADMIN, REPORTS_REVENUE, VIEW, EXPORT);
        grant(defaults, ADMIN, USER_MANAGEMENT, VIEW, CREATE, EDIT, DELETE);
        grant(defaults, ADMIN, ACCESS_MATRIX, VIEW);

        // ── RECEPTIONIST — front desk: bookings, patients, payments; no catalog/master-data edit,
        // no commission/revenue ₹ visibility (enforced at template level, not this module gate) ──
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
        grant(defaults, RECEPTIONIST, REPORTS_STANDARD, VIEW, EXPORT);

        // ── THERAPIST — own schedule/earnings only (row-level "own" scoping is Phase C); no
        // master-data edit rights, no Tags, no revenue report, no user/matrix admin ──
        grant(defaults, THERAPIST, DASHBOARD, VIEW);
        grant(defaults, THERAPIST, PATIENTS, VIEW);
        grant(defaults, THERAPIST, APPOINTMENTS, VIEW, EDIT, APPROVE);
        grant(defaults, THERAPIST, THERAPISTS, VIEW);
        grant(defaults, THERAPIST, SERVICES, VIEW);
        grant(defaults, THERAPIST, PRODUCTS, VIEW);
        grant(defaults, THERAPIST, COMBOS, VIEW);
        grant(defaults, THERAPIST, PACKAGE_TEMPLATES, VIEW);
        grant(defaults, THERAPIST, PATIENT_PACKAGES, VIEW);
        grant(defaults, THERAPIST, WALLET, VIEW);
        grant(defaults, THERAPIST, REPORTS_STANDARD, VIEW, EXPORT);

        rolePermissionRepository.saveAll(defaults);
        log.info("Seeded {} default role-permission rows.", defaults.size());
    }

    private void grant(List<RolePermission> list, AppRole role, Module module, PermissionAction... actions) {
        for (PermissionAction action : actions) {
            list.add(RolePermission.builder().role(role).module(module).action(action).granted(true).build());
        }
    }
}