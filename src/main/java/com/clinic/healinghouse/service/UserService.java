package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.UserForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.TherapistRepository;
import com.clinic.healinghouse.repository.UserRepository;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User account management (requirements/Security_RBAC_Requirements_v1.md §8.1) — create/edit/
 * disable/enable/reset-password for {@code User} logins. Business rules deliberately live here
 * rather than in the controller, since they're cross-field (role vs. therapist link) and
 * cross-request (ADMIN-cannot-touch-OWNER, self-disable) checks that don't fit a single
 * {@code @Valid} annotation the way the rest of this codebase's simpler master-data forms do.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final TherapistRepository therapistRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;

    @Transactional(readOnly = true)
    public Page<User> findAll(boolean showInactive, Pageable pageable) {
        return showInactive
                ? userRepository.findAllByOrderByUsernameAsc(pageable)
                : userRepository.findByActiveTrueOrderByUsernameAsc(pageable);
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }

    /** Active therapists not already linked to a different user account — backs the THERAPIST-role
     *  picker on the create/edit form (§8.1: "requires picking an existing Therapist record ...
     *  not already linked to another user"). {@code excludingUserId} keeps the currently-linked
     *  therapist selectable when editing that same user. */
    @Transactional(readOnly = true)
    public List<Therapist> getAvailableTherapistsForLinking(Long excludingUserId) {
        Set<Long> linkedElsewhere = new HashSet<>();
        for (User u : userRepository.findAll()) {
            if (u.getTherapist() != null && !u.getId().equals(excludingUserId)) {
                linkedElsewhere.add(u.getTherapist().getId());
            }
        }
        return therapistRepository.findByActiveTrueOrderByFullNameAsc().stream()
                .filter(t -> !linkedElsewhere.contains(t.getId()))
                .toList();
    }

    public User create(UserForm form) {
        AppRole role = parseRole(form.getRole());
        requireCanManage(role);
        validateTherapistLinkage(role, form.getTherapistId(), null);
        requireUsernameAvailable(form.getUsername(), null);
        if (form.getPassword() == null || form.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }

        Therapist therapist = resolveTherapist(form.getTherapistId());
        User user = User.builder()
                .username(form.getUsername().trim())
                .passwordHash(passwordEncoder.encode(form.getPassword()))
                .fullName(form.getFullName())
                .role(role)
                .therapist(therapist)
                .active(true)
                .mustChangePassword(true)
                .build();
        User saved = userRepository.save(user);
        log.info("Created user id={} username='{}' role={}", saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    public User update(UserForm form) {
        User existing = getById(form.getId());
        AppRole newRole = parseRole(form.getRole());
        // Both the account being edited and the role it's being moved to are checked — an ADMIN
        // can't edit an existing OWNER account, and can't promote someone else's account to OWNER either.
        requireCanManage(existing.getRole());
        requireCanManage(newRole);
        validateTherapistLinkage(newRole, form.getTherapistId(), existing.getId());
        requireUsernameAvailable(form.getUsername(), existing.getId());

        existing.setUsername(form.getUsername().trim());
        existing.setFullName(form.getFullName());
        existing.setRole(newRole);
        existing.setTherapist(resolveTherapist(form.getTherapistId()));
        User saved = userRepository.save(existing);
        log.info("Updated user id={} username='{}'", saved.getId(), saved.getUsername());
        return saved;
    }

    public void disable(Long id) {
        User user = getById(id);
        requireCanManage(user.getRole());
        if (user.getId().equals(currentUserIdOrNull())) {
            throw new IllegalArgumentException("You can't disable your own account.");
        }
        user.setActive(false);
        userRepository.save(user);
        log.info("Disabled user id={} username='{}'", user.getId(), user.getUsername());
    }

    public void enable(Long id) {
        User user = getById(id);
        requireCanManage(user.getRole());
        user.setActive(true);
        userRepository.save(user);
        log.info("Re-enabled user id={} username='{}'", user.getId(), user.getUsername());
    }

    public void resetPassword(Long id, String newPassword) {
        User user = getById(id);
        requireCanManage(user.getRole());
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        log.info("Password reset for user id={} username='{}'", user.getId(), user.getUsername());
    }

    // ── Validation helpers ────────────────────────────────────────────────

    private AppRole parseRole(String raw) {
        try {
            return AppRole.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Unknown role: " + raw);
        }
    }

    /** ADMIN may act on every role except OWNER (§4, §8.1); OWNER has no restriction. This is
     *  overridden by the caller's ACTUAL role, read fresh each call rather than passed in, since
     *  every entry point here is a direct controller action by the currently authenticated user. */
    private void requireCanManage(AppRole targetRole) {
        if (currentRoleOrNull() == AppRole.ADMIN && targetRole == AppRole.OWNER) {
            throw new IllegalArgumentException("Admins cannot manage Owner accounts.");
        }
    }

    private void validateTherapistLinkage(AppRole role, Long therapistId, Long excludingUserId) {
        if (role == AppRole.THERAPIST) {
            if (therapistId == null) {
                throw new IllegalArgumentException("A Therapist-role user must be linked to a therapist record.");
            }
            userRepository.findByTherapistId(therapistId)
                    .filter(u -> !u.getId().equals(excludingUserId))
                    .ifPresent(u -> {
                        throw new IllegalArgumentException("That therapist is already linked to another user account.");
                    });
        } else if (therapistId != null) {
            throw new IllegalArgumentException("Only a Therapist-role user can be linked to a therapist record.");
        }
    }

    private void requireUsernameAvailable(String username, Long excludingUserId) {
        userRepository.findByUsernameIgnoreCase(username)
                .filter(u -> !u.getId().equals(excludingUserId))
                .ifPresent(u -> {
                    throw new IllegalArgumentException("Username '" + username + "' is already taken.");
                });
    }

    private Therapist resolveTherapist(Long therapistId) {
        if (therapistId == null) return null;
        return therapistRepository.findById(therapistId)
                .orElseThrow(() -> new IllegalArgumentException("Therapist not found: " + therapistId));
    }

    private AppRole currentRoleOrNull() {
        return permissionService.currentRole();
    }

    private Long currentUserIdOrNull() {
        return permissionService.currentUserId();
    }
}
