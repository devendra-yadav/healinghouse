package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a controller method behind a (module, action) cell of the Access Control Matrix
 * (requirements/Security_RBAC_Requirements_v1.md §7). {@link PermissionAspect} checks it before
 * the method runs. Endpoints whose single handler serves two different matrix actions (e.g. a
 * shared "save" method used for both CREATE and EDIT) can't be expressed with one annotation —
 * those call {@code PermissionService.require(...)} inline instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {
    Module module();
    PermissionAction action();
}
