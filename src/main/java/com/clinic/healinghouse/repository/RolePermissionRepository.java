package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    boolean existsByRoleAndModuleAndAction(AppRole role, Module module, PermissionAction action);

    Optional<RolePermission> findByRoleAndModuleAndAction(AppRole role, Module module, PermissionAction action);
}
