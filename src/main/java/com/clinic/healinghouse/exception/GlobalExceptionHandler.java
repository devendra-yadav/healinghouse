package com.clinic.healinghouse.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Last-resort safety net for MVC (non-@ResponseBody) endpoints that don't already handle their
 * own exceptions locally — e.g. a stale "Edit"/"Delete" link on master-data pages (#6), or any
 * uncaught exception that would otherwise surface as Spring's Whitelabel stack-trace page (#5).
 * JSON/@ResponseBody endpoints (e.g. ComboController.detail) handle their own not-found case
 * locally instead, since a redirect response is meaningless to a fetch() caller.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public String handleNotFound(EntityNotFoundException ex, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Entity not found: {}", ex.getMessage());
        ra.addFlashAttribute("errorMessage", "The record you were looking for doesn't exist or was already removed.");
        return "redirect:" + fallbackUrl(request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request, RedirectAttributes ra) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        ra.addFlashAttribute("errorMessage", "This action conflicts with existing data (e.g. a duplicate value). Please review and try again.");
        return "redirect:" + fallbackUrl(request);
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, HttpServletResponse response, Model model) {
        log.error("Unhandled exception", ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("pageTitle", "Error");
        return "error";
    }

    private static String fallbackUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        return (referer != null && !referer.isBlank()) ? referer : "/";
    }
}
