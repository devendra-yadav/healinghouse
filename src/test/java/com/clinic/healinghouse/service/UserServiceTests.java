package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.UserForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.TherapistRepository;
import com.clinic.healinghouse.repository.UserRepository;
import com.clinic.healinghouse.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock private UserRepository userRepository;
    @Mock private TherapistRepository therapistRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PermissionService permissionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, therapistRepository, passwordEncoder, permissionService);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
    }

    private Therapist therapist(long id) {
        return Therapist.builder().id(id).fullName("Priya Sharma").active(true).build();
    }

    private UserForm formFor(AppRole role, Long therapistId, String password) {
        UserForm form = new UserForm();
        form.setUsername("priya");
        form.setFullName("Priya Sharma");
        form.setRole(role.name());
        form.setTherapistId(therapistId);
        form.setPassword(password);
        return form;
    }

    // ── Create: therapist linkage validation ──

    @Test
    void createThrowsWhenTherapistRoleHasNoLinkedTherapist() {
        assertThatThrownBy(() -> userService.create(formFor(AppRole.THERAPIST, null, "password1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be linked to a therapist");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenNonTherapistRoleHasTherapistIdSet() {
        assertThatThrownBy(() -> userService.create(formFor(AppRole.RECEPTIONIST, 5L, "password1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only a Therapist-role user");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenTherapistAlreadyLinkedToAnotherUser() {
        when(userRepository.findByTherapistId(5L))
                .thenReturn(Optional.of(User.builder().id(99L).username("other").build()));

        assertThatThrownBy(() -> userService.create(formFor(AppRole.THERAPIST, 5L, "password1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already linked");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createSucceedsWithValidTherapistLinkage() {
        when(userRepository.findByTherapistId(5L)).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("priya")).thenReturn(Optional.empty());
        when(therapistRepository.findById(5L)).thenReturn(Optional.of(therapist(5L)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.create(formFor(AppRole.THERAPIST, 5L, "password1"));

        assertThat(saved.getTherapist().getId()).isEqualTo(5L);
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.isActive()).isTrue();
    }

    // ── Create: duplicate username / weak password ──

    @Test
    void createThrowsOnDuplicateUsername() {
        when(userRepository.findByUsernameIgnoreCase("priya"))
                .thenReturn(Optional.of(User.builder().id(1L).username("priya").build()));

        assertThatThrownBy(() -> userService.create(formFor(AppRole.RECEPTIONIST, null, "password1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void createThrowsOnShortPassword() {
        when(userRepository.findByUsernameIgnoreCase("priya")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(formFor(AppRole.RECEPTIONIST, null, "short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8 characters");
    }

    // ── ADMIN cannot manage OWNER accounts ──

    @Test
    void adminCannotCreateAnOwnerAccount() {
        when(permissionService.currentRole()).thenReturn(AppRole.ADMIN);

        assertThatThrownBy(() -> userService.create(formFor(AppRole.OWNER, null, "password1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot manage Owner accounts");
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotEditAnExistingOwnerAccount() {
        User existingOwner = User.builder().id(3L).username("owner").role(AppRole.OWNER).build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(existingOwner));
        when(permissionService.currentRole()).thenReturn(AppRole.ADMIN);

        UserForm form = formFor(AppRole.OWNER, null, null);
        form.setId(3L);

        assertThatThrownBy(() -> userService.update(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot manage Owner accounts");
        verify(userRepository, never()).save(any());
    }

    @Test
    void ownerCanCreateAnOwnerAccount() {
        when(permissionService.currentRole()).thenReturn(AppRole.OWNER);
        when(userRepository.findByUsernameIgnoreCase("priya")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.create(formFor(AppRole.OWNER, null, "password1"));

        assertThat(saved.getRole()).isEqualTo(AppRole.OWNER);
    }

    // ── Self-disable guard ──

    @Test
    void disableThrowsWhenTargetIsTheCurrentUser() {
        User self = User.builder().id(7L).username("me").role(AppRole.ADMIN).active(true).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(self));
        when(permissionService.currentUserId()).thenReturn(7L);

        assertThatThrownBy(() -> userService.disable(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own account");
        verify(userRepository, never()).save(any());
    }

    @Test
    void disableSucceedsForAnotherUser() {
        User other = User.builder().id(8L).username("them").role(AppRole.RECEPTIONIST).active(true).build();
        when(userRepository.findById(8L)).thenReturn(Optional.of(other));
        when(permissionService.currentUserId()).thenReturn(7L);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.disable(8L);

        assertThat(other.isActive()).isFalse();
    }

    // ── getAvailableTherapistsForLinking excludes therapists already linked to a different user ──

    @Test
    void availableTherapistsExcludesThoseLinkedToAnotherUser() {
        Therapist linked = therapist(1L);
        Therapist free = therapist(2L);
        User linkedUser = User.builder().id(50L).therapist(linked).build();
        when(userRepository.findAll()).thenReturn(java.util.List.of(linkedUser));
        when(therapistRepository.findByActiveTrueOrderByFullNameAsc()).thenReturn(java.util.List.of(linked, free));

        var available = userService.getAvailableTherapistsForLinking(null);

        assertThat(available).extracting(Therapist::getId).containsExactly(2L);
    }

    @Test
    void availableTherapistsIncludesTherapistCurrentlyLinkedToTheUserBeingEdited() {
        Therapist linked = therapist(1L);
        User linkedUser = User.builder().id(50L).therapist(linked).build();
        when(userRepository.findAll()).thenReturn(java.util.List.of(linkedUser));
        when(therapistRepository.findByActiveTrueOrderByFullNameAsc()).thenReturn(java.util.List.of(linked));

        var available = userService.getAvailableTherapistsForLinking(50L);

        assertThat(available).extracting(Therapist::getId).containsExactly(1L);
    }
}
