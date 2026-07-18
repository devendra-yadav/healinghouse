package com.clinic.healinghouse.config;

import com.clinic.healinghouse.security.MustChangePasswordFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Phase A: authentication is enforced for every route via this filter chain. Phase B layers
 * fine-grained (module, action) gating on top via {@code @RequiresPermission} + {@code PermissionAspect}
 * (requirements/Security_RBAC_Requirements_v1.md §7, §12) — {@code @EnableAspectJAutoProxy} is
 * declared explicitly here rather than relied on via Boot's AOP autoconfiguration, since a plain
 * {@code @Aspect} bean with no other trigger wasn't being proxied without it.
 */
@Configuration
@EnableWebSecurity
@EnableAspectJAutoProxy
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Tracks every live HTTP session against the {@code UserPrincipal} that created it, so
     * {@code UserService} can force-expire a specific user's sessions the moment they're
     * disabled, password-reset, or role-changed by an admin — otherwise Spring Security only
     * ever re-checks {@code UserDetails} at authentication time, and a stale session keeps
     * whatever access it started with until it times out on its own (up to 30 minutes).
     * Requires {@link HttpSessionEventPublisher} below so destroyed sessions are pruned from
     * the registry too.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .requestMatchers("/login").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", false)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // maximumSessions(-1): no cap on concurrent sessions per user — this is only here to
            // wire up the registry + ConcurrentSessionFilter so sessions can be force-expired
            // on demand (see UserService.invalidateSessionsForUser), not to limit legitimate use.
            .sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
                .expiredUrl("/login")
            )
            // Runs once a request is authenticated — redirects to /account/change-password while
            // User.mustChangePassword is still true (see MustChangePasswordFilter's javadoc).
            .addFilterAfter(new MustChangePasswordFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}