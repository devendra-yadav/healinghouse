package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.util.SafeRedirectUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * A THERAPIST/THERAPIST_PLUS user's "Therapists" page shows their own commission/earnings detail —
 * sensitive enough that leaving it reachable on an unattended, still-logged-in clinic computer is a
 * real risk (a different person at the same terminal would see it without ever entering a password).
 * {@code TherapistController} already scopes these roles to their own profile only, so this isn't an
 * access-control gap; it's a step-up ("confirm it's still you") check layered on top of an otherwise
 * valid session, matching how banks re-prompt for a PIN before showing an account balance.
 *
 * Also covers {@code /contracts/**} for the same reason: a therapist's own Employment Contract page
 * shows the identical class of sensitive payout data (salary, commission %, bonus terms) plus the
 * signed PDF, and is reachable by a link from the very Therapist detail page this filter already
 * gates — without this, bookmarking or leaving that contract page open would bypass the protection
 * entirely (Bug_Report_v7.md Finding 6).
 *
 * Redirects any matching GET to {@code /account/confirm-password} unless the session already has a
 * recent (within {@link #VALIDITY}) successful confirmation — see
 * {@code AccountController#confirmPasswordSubmit}, which sets {@link #SESSION_ATTR}. Deliberately
 * short-lived rather than a one-time gate: an indefinitely-trusted session would defeat the point on
 * the very same unattended-computer scenario this exists for.
 */
public class TherapistStepUpAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTR = "THERAPIST_STEP_UP_CONFIRMED_AT";
    static final Duration VALIDITY = Duration.ofMinutes(5);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());

        boolean coveredPath = path.startsWith("/therapists") || path.startsWith("/contracts");
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !coveredPath) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        AppRole role = principal.getRole();
        if (role != AppRole.THERAPIST && role != AppRole.THERAPIST_PLUS) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        Instant confirmedAt = session != null ? (Instant) session.getAttribute(SESSION_ATTR) : null;
        if (confirmedAt != null && confirmedAt.plus(VALIDITY).isAfter(Instant.now())) {
            chain.doFilter(request, response);
            return;
        }

        String returnUrl = path + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        String safeReturnUrl = SafeRedirectUtil.sanitize(returnUrl, "/therapists");
        response.sendRedirect(contextPath + "/account/confirm-password?returnUrl="
                + URLEncoder.encode(safeReturnUrl, StandardCharsets.UTF_8));
    }
}
