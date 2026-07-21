package com.clinic.healinghouse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces {@code User.mustChangePassword} (finding #9 in Bug_Report_v4.md): the flag was being set
 * on account creation and admin password-reset but nothing ever read it, so a temp/admin-issued
 * password silently became permanent. Redirects any authenticated request carrying the flag to the
 * self-service change-password page, except for that page itself and logout/static assets.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri.substring(contextPath.length());

        if (isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal
                && principal.getUser().isMustChangePassword()) {
            response.sendRedirect(contextPath + "/account/change-password");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isExempt(String path) {
        return path.equals("/account/change-password")
                || path.equals("/login")
                || path.equals("/logout")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/webjars/");
    }
}
