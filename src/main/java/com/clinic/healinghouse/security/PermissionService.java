package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.repository.RolePermissionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for "can the current user do X" — backed by an in-memory cache of
 * {@code RolePermission} rows (requirements/Security_RBAC_Requirements_v1.md §6.3, §7), so a
 * permission check never costs a DB round-trip. Loaded once at startup and reloaded whenever the
 * Access Matrix UI (Phase D) saves a change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final RolePermissionRepository rolePermissionRepository;

    private volatile Map<AppRole, Set<ModuleAction>> cache = Map.of();

    private record ModuleAction(Module module, PermissionAction action) {
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** Re-reads every RolePermission row from the DB. Call after any Access Matrix save. */
    public synchronized void reload() {
        Map<AppRole, Set<ModuleAction>> next = new EnumMap<>(AppRole.class);
        for (AppRole role : AppRole.values()) {
            next.put(role, new HashSet<>());
        }
        rolePermissionRepository.findAll().forEach(rp -> {
            if (rp.isGranted()) {
                next.get(rp.getRole()).add(new ModuleAction(rp.getModule(), rp.getAction()));
            }
        });
        this.cache = next;
        log.info("Permission cache reloaded ({} roles).", next.size());
    }

    public boolean has(Module module, PermissionAction action) {
        AppRole role = currentRole();
        if (role == null) return false;
        return cache.getOrDefault(role, Set.of()).contains(new ModuleAction(module, action));
    }

    /** Throws {@link AccessDeniedException} (handled by GlobalExceptionHandler) if not granted —
     *  used both by {@link PermissionAspect} and inline in controllers whose single handler method
     *  covers more than one matrix action (e.g. a shared create/edit "save" endpoint). */
    public void require(Module module, PermissionAction action) {
        if (!has(module, action)) {
            AppRole role = currentRole();
            log.warn("Permission denied: user='{}' role={} module={} action={}",
                    currentUsername(), role, module, action);
            throw new AccessDeniedException("You don't have permission to perform this action.");
        }
    }

    /** The logged-in username, or {@code "anonymous"} if unauthenticated — for log messages only;
     *  business code should use {@link #currentUserId()} instead. */
    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return "anonymous";
        }
        return principal.getUsername();
    }

    /** The logged-in user's role, or {@code null} if unauthenticated — public because Phase D's
     *  User Management service also needs it (the "ADMIN cannot manage OWNER accounts" rule, §8.1),
     *  not just this class's own {@link #has} check. */
    public AppRole currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal.getRole();
    }

    /** The logged-in {@code User.id}, or {@code null} if unauthenticated — lets a controller/service
     *  guard "can't act on your own account" cases (e.g. disabling yourself) without a separate lookup. */
    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal.getId();
    }

    /** The logged-in user's linked {@code Therapist.id} when they're a THERAPIST, else {@code null} —
     *  the scoping key every Phase C "own data" row-level filter resolves against
     *  (requirements/Security_RBAC_Requirements_v1.md §7, §12 Phase C). Non-THERAPIST roles always
     *  get {@code null} here since {@code User.therapist} is null for them, so callers can use this
     *  as a single "am I scoped, and to what id" check without a separate role branch. */
    public Long currentTherapistId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal.getTherapistId();
    }
}
