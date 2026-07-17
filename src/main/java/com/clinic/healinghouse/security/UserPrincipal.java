package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Spring Security's view of a {@link User} row — the authorities/lock/enabled checks the
 *  authentication filter chain reads. Business code that needs the underlying id/role/linked
 *  therapist reads {@link #getUser()} rather than re-deriving them from getAuthorities(). */
@Getter
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    public Long getId() {
        return user.getId();
    }

    public AppRole getRole() {
        return user.getRole();
    }

    public Long getTherapistId() {
        return user.getTherapist() != null ? user.getTherapist().getId() : null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        LocalDateTime lockedUntil = user.getLockedUntil();
        return lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }
}