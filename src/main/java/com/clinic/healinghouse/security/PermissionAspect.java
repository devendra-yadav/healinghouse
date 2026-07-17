package com.clinic.healinghouse.security;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/** Enforces {@link RequiresPermission} on every annotated controller method before it runs
 *  (requirements/Security_RBAC_Requirements_v1.md §7). Denial throws AccessDeniedException,
 *  handled by GlobalExceptionHandler the same way its other exception types already are. */
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionService permissionService;

    @Before("@annotation(requiresPermission)")
    public void checkPermission(RequiresPermission requiresPermission) {
        permissionService.require(requiresPermission.module(), requiresPermission.action());
    }
}
