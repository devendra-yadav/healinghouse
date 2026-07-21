package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import com.clinic.healinghouse.repository.RolePermissionRepository;
import com.clinic.healinghouse.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Backs the OWNER-only Access Matrix editor (requirements/Security_RBAC_Requirements_v1.md §8.2).
 * Saving toggles {@code RolePermission.granted} rows and reloads {@link PermissionService}'s cache
 * in the same transaction, so the exit check ("toggling a permission off takes effect on the very
 * next request, no restart needed") holds even under a currently-open session for another user.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AccessMatrixService {

    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionService permissionService;

    @Transactional(readOnly = true)
    public List<RolePermission> findAll() {
        return rolePermissionRepository.findAll();
    }

    /** {@code grantedIds} is every checked checkbox's {@code RolePermission.id} — an unchecked box
     *  simply doesn't appear in the submitted form, so every id NOT in this set is set to false. */
    public void save(Set<Long> grantedIds) {
        List<RolePermission> all = rolePermissionRepository.findAll();
        for (RolePermission rp : all) {
            boolean granted = grantedIds.contains(rp.getId());
            // The one cell with no recovery path if switched off: OWNER's own ability to edit this
            // matrix. There's no other UI (short of a DB edit) to turn it back on, so it's pinned on.
            if (rp.getRole() == AppRole.OWNER && rp.getModule() == Module.ACCESS_MATRIX
                    && rp.getAction() == PermissionAction.EDIT) {
                granted = true;
            }
            rp.setGranted(granted);
        }
        rolePermissionRepository.saveAll(all);
        permissionService.reload();
        log.info("Access matrix saved — {} permission rows granted of {} total.",
                all.stream().filter(RolePermission::isGranted).count(), all.size());
    }
}
