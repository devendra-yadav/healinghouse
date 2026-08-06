package com.clinic.healinghouse.security;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link TherapistStepUpAuthFilter} gates the "Therapists" page for a THERAPIST/THERAPIST_PLUS
 *  session on a shared clinic computer — see its javadoc for the scenario this closes. */
class TherapistStepUpAuthFilterTests {

    private final TherapistStepUpAuthFilter filter = new TherapistStepUpAuthFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UserPrincipal principal(AppRole role) {
        return new UserPrincipal(User.builder().id(1L).username("priya").role(role).build());
    }

    private void authenticateAs(AppRole role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal(role), null, principal(role).getAuthorities()));
    }

    private HttpServletRequest requestFor(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContextPath()).thenReturn("");
        return request;
    }

    @Test
    void nonGetRequestsPassThroughUnchecked() throws Exception {
        authenticateAs(AppRole.THERAPIST);
        HttpServletRequest request = requestFor("POST", "/therapists/1/delete");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void pathsOutsideTherapistsPassThroughUnchecked() throws Exception {
        authenticateAs(AppRole.THERAPIST);
        HttpServletRequest request = requestFor("GET", "/appointments");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void nonTherapistRolesPassThroughUnchecked() throws Exception {
        authenticateAs(AppRole.RECEPTIONIST);
        HttpServletRequest request = requestFor("GET", "/therapists/1");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void therapistWithNoPriorConfirmationIsRedirectedToConfirmPassword() throws Exception {
        authenticateAs(AppRole.THERAPIST);
        HttpServletRequest request = requestFor("GET", "/therapists/1");
        when(request.getSession(false)).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect("/account/confirm-password?returnUrl=%2Ftherapists%2F1");
    }

    @Test
    void therapistPlusWithRecentConfirmationPassesThrough() throws Exception {
        authenticateAs(AppRole.THERAPIST_PLUS);
        HttpServletRequest request = requestFor("GET", "/therapists/1");
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(TherapistStepUpAuthFilter.SESSION_ATTR)).thenReturn(Instant.now());
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void therapistWithExpiredConfirmationIsRedirectedAgain() throws Exception {
        authenticateAs(AppRole.THERAPIST);
        HttpServletRequest request = requestFor("GET", "/therapists/1");
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(TherapistStepUpAuthFilter.SESSION_ATTR))
                .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).sendRedirect(anyString());
    }
}
