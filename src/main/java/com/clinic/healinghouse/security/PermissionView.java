package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Exposes {@link PermissionService} to Thymeleaf as {@code @perm} (requirements/Security_RBAC_Requirements_v1.md
 *  §7) so templates can hide nav links/buttons a user's role can't act on, e.g.
 *  {@code th:if="${@perm.has('APPOINTMENTS','DELETE')}"}. String args (not the enums directly) since
 *  that's what a Thymeleaf SpEL literal can pass; an unknown/typo'd name fails closed (denied). */
@Component("perm")
@RequiredArgsConstructor
public class PermissionView {

    private final PermissionService permissionService;

    public boolean has(String module, String action) {
        try {
            return permissionService.has(Module.valueOf(module), PermissionAction.valueOf(action));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The logged-in THERAPIST's own {@code Therapist.id}, or {@code null} for every other role —
     *  lets a template branch to "my calendar" (e.g. the dashboard's calendar link) without a
     *  controller-side model attribute. */
    public Long currentTherapistId() {
        return permissionService.currentTherapistId();
    }
}
