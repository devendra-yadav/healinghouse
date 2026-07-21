package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import com.clinic.healinghouse.repository.RolePermissionRepository;
import com.clinic.healinghouse.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessMatrixServiceTests {

    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private PermissionService permissionService;

    private AccessMatrixService accessMatrixService;

    @BeforeEach
    void setUp() {
        accessMatrixService = new AccessMatrixService(rolePermissionRepository, permissionService);
    }

    private RolePermission row(long id, AppRole role, Module module, PermissionAction action, boolean granted) {
        return RolePermission.builder().id(id).role(role).module(module).action(action).granted(granted).build();
    }

    @Test
    void saveGrantsOnlySubmittedIdsAndRevokesEverythingElse() {
        RolePermission ownerView = row(1, AppRole.OWNER, Module.DASHBOARD, PermissionAction.VIEW, true);
        RolePermission receptionistCreate = row(2, AppRole.RECEPTIONIST, Module.PATIENTS, PermissionAction.CREATE, true);
        when(rolePermissionRepository.findAll()).thenReturn(List.of(ownerView, receptionistCreate));

        accessMatrixService.save(Set.of(1L));

        assertThat(ownerView.isGranted()).isTrue();
        assertThat(receptionistCreate.isGranted()).isFalse();
        verify(rolePermissionRepository).saveAll(List.of(ownerView, receptionistCreate));
    }

    @Test
    void saveAlwaysKeepsOwnerAccessMatrixEditGrantedEvenIfNotSubmitted() {
        RolePermission ownerMatrixEdit = row(9, AppRole.OWNER, Module.ACCESS_MATRIX, PermissionAction.EDIT, true);
        when(rolePermissionRepository.findAll()).thenReturn(List.of(ownerMatrixEdit));

        // Simulates an OWNER accidentally unchecking their own matrix-edit box — there's no UI path
        // back if this were allowed through, so it's pinned on regardless of what was submitted.
        accessMatrixService.save(Set.of());

        assertThat(ownerMatrixEdit.isGranted()).isTrue();
    }

    @Test
    void saveReloadsThePermissionCacheSoTheChangeTakesEffectImmediately() {
        when(rolePermissionRepository.findAll()).thenReturn(List.of());

        accessMatrixService.save(Set.of());

        verify(permissionService).reload();
    }
}
