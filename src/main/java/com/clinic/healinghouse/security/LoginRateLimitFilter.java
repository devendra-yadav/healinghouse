package com.clinic.healinghouse.security;

import com.clinic.healinghouse.config.HealingHouseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-IP sliding-window throttle in front of POST /login, independent of the account-level lockout
 * in {@code LoginAttemptListener}. Closes the residual gap in Bug_Report_v4.md #3: that fix only
 * stopped an already-locked account's lock from being *extended* by further attempts; it didn't
 * stop an attacker from re-locking a known username (e.g. the default "owner") every time the
 * 15-minute window naturally expires, nor from spraying guesses across many usernames from one IP.
 * This filter counts every POST /login from an IP regardless of username or outcome, and rejects
 * once the window's cap is hit — before the request ever reaches Spring Security's authentication
 * filter, so a throttled burst can't even register as failed-login events.
 */
@Slf4j
@RequiredArgsConstructor
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final HealingHouseProperties properties;
    private final ConcurrentHashMap<String, Deque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoginPost(request)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        Deque<Instant> attempts = attemptsByIp.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        Instant windowStart = Instant.now().minusSeconds(properties.getSecurity().getLoginRateLimitWindowMinutes() * 60L);

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }
            if (attempts.size() >= properties.getSecurity().getMaxLoginAttemptsPerIp()) {
                log.warn("Login rate limit exceeded for IP {}", ip);
                response.sendRedirect(request.getContextPath() + "/login?error=rate-limit");
                return;
            }
            attempts.addLast(Instant.now());
        }

        chain.doFilter(request, response);
    }

    private boolean isLoginPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && (request.getContextPath() + "/login").equals(request.getRequestURI());
    }
}
