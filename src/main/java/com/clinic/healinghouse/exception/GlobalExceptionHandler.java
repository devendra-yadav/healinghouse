package com.clinic.healinghouse.exception;

import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.Map;

/**
 * Last-resort safety net for endpoints that don't already handle their own exceptions locally —
 * e.g. a stale "Edit"/"Delete" link on master-data pages (#6), or any uncaught exception that
 * would otherwise surface as Spring's Whitelabel stack-trace page (#5). Branches its response
 * format on whether the throwing handler is a JSON endpoint (any @ResponseBody method — the
 * typeahead/autocomplete search endpoints on PatientController/ComboController/TagController,
 * plus ComboController.detail which additionally still handles its own not-found/409 cases
 * locally for a more specific message): those get a small JSON error body instead of the
 * redirect/HTML a normal page controller gets, since a redirect or HTML response is meaningless
 * to a fetch() caller and previously broke it with a silent JSON-parse failure (Bug_Report_v2 #9).
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(EntityNotFoundException.class)
    public String handleNotFound(EntityNotFoundException ex, HttpServletRequest request,
                                  HttpServletResponse response, HandlerMethod handlerMethod,
                                  RedirectAttributes ra) throws IOException {
        log.warn("Entity not found: {}", ex.getMessage());
        String message = "The record you were looking for doesn't exist or was already removed.";
        if (expectsJson(handlerMethod)) {
            writeJsonError(response, HttpStatus.NOT_FOUND, message);
            return null;
        }
        ra.addFlashAttribute("errorMessage", message);
        return "redirect:" + fallbackUrl(request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request,
                                                HttpServletResponse response, HandlerMethod handlerMethod,
                                                RedirectAttributes ra) throws IOException {
        log.warn("Data integrity violation: {}", ex.getMessage());
        String message = "This action conflicts with existing data (e.g. a duplicate value). Please review and try again.";
        if (expectsJson(handlerMethod)) {
            writeJsonError(response, HttpStatus.CONFLICT, message);
            return null;
        }
        ra.addFlashAttribute("errorMessage", message);
        return "redirect:" + fallbackUrl(request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, HttpServletRequest request,
                                      HttpServletResponse response, HandlerMethod handlerMethod,
                                      RedirectAttributes ra) throws IOException {
        log.warn("Access denied: {}", ex.getMessage());
        String message = "You don't have permission to perform this action.";
        if (expectsJson(handlerMethod)) {
            writeJsonError(response, HttpStatus.FORBIDDEN, message);
            return null;
        }
        ra.addFlashAttribute("errorMessage", message);
        return "redirect:" + fallbackUrl(request);
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, HttpServletResponse response,
                                    HandlerMethod handlerMethod, Model model) throws IOException {
        log.error("Unhandled exception", ex);
        if (expectsJson(handlerMethod)) {
            writeJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
            return null;
        }
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("pageTitle", "Error");
        return "error";
    }

    /** True for any endpoint whose response Spring would otherwise serialize as JSON, not render as a view. */
    private static boolean expectsJson(HandlerMethod handlerMethod) {
        return handlerMethod != null
                && (handlerMethod.hasMethodAnnotation(ResponseBody.class)
                    || handlerMethod.getBeanType().isAnnotationPresent(ResponseBody.class));
    }

    /** Writes the response directly (bypassing view resolution) — pairs with returning null above. */
    private void writeJsonError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", message)));
    }

    /** Only honors Referer when it's same-origin — an attacker-controlled cross-origin value falls back to "/". */
    private static String fallbackUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return "/";
        }
        if (referer.startsWith("/") && !referer.startsWith("//")) {
            return referer;
        }
        try {
            java.net.URI uri = java.net.URI.create(referer);
            if (uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && uri.getScheme() != null
                    && uri.getScheme().equalsIgnoreCase(request.getScheme())) {
                return referer;
            }
        } catch (IllegalArgumentException ignored) {
            // malformed Referer — fall through to "/"
        }
        return "/";
    }
}
