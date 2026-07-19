package com.clinic.healinghouse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the current request's actor (username, role) into SLF4J's MDC so every log line emitted
 * while handling the request — controller, service, aspect, exception handler alike — carries
 * "who" without each call site having to fetch and pass it explicitly. Pairs with the %X{user}/
 * %X{role} tokens added to the console/file log patterns (application.yml, logback-spring.xml).
 * Cleared in a finally block since the servlet container reuses request-handling threads across
 * requests (thread pool), so stale MDC would otherwise leak into an unrelated later request.
 */
public class MdcUserContextFilter extends OncePerRequestFilter {

    static final String USER_KEY = "user";
    static final String ROLE_KEY = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            MDC.put(USER_KEY, principal.getUsername());
            MDC.put(ROLE_KEY, principal.getRole().name());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(USER_KEY);
            MDC.remove(ROLE_KEY);
        }
    }
}
