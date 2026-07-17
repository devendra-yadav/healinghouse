package com.clinic.healinghouse.security;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Account lockout (§5, §11 decision 7's audit-adjacent rationale): 5 consecutive failed attempts
 * locks the account for 15 minutes (both configurable via healinghouse.security.*). A successful
 * login always resets the counter — only *consecutive* failures count, matching how most lockout
 * policies behave and avoiding permanently penalizing a user who mistypes once weeks apart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptListener {

    private final UserRepository userRepository;
    private final HealingHouseProperties properties;

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication().getName();
        userRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= properties.getSecurity().getMaxFailedLoginAttempts()) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(properties.getSecurity().getLockoutMinutes()));
                log.warn("User '{}' locked out for {} minutes after {} failed login attempts",
                        username, properties.getSecurity().getLockoutMinutes(), attempts);
            }
            userRepository.save(user);
        });
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        userRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }
}