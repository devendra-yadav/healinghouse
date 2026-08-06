package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.ChangePasswordForm;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.TherapistStepUpAuthFilter;
import com.clinic.healinghouse.security.UserPrincipal;
import com.clinic.healinghouse.service.UserService;
import com.clinic.healinghouse.util.SafeRedirectUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;

/**
 * Self-service change-password — every authenticated user's own account, regardless of role/module
 * permissions (this is not gated by {@code @RequiresPermission}, unlike admin-driven user management
 * in {@link UserAdminController}). Closes finding #9 in Bug_Report_v4.md: {@code mustChangePassword}
 * was set on create/reset but nothing ever read it — {@code security.MustChangePasswordFilter} now
 * redirects here until the user complies.
 */
@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
class AccountController {

    private final UserService userService;
    private final PermissionService permissionService;

    @GetMapping("/change-password")
    public String form(Model model) {
        model.addAttribute("changePasswordForm", new ChangePasswordForm());
        model.addAttribute("pageTitle", "Change Password");
        return "auth/change-password";
    }

    @PostMapping("/change-password")
    public String submit(@ModelAttribute("changePasswordForm") ChangePasswordForm form,
                          Model model, RedirectAttributes ra) {
        try {
            userService.changeOwnPassword(permissionService.currentUserId(),
                    form.getCurrentPassword(), form.getNewPassword(), form.getConfirmNewPassword());
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("pageTitle", "Change Password");
            return "auth/change-password";
        }
        // The session's UserPrincipal wraps a User row fetched at login time, distinct from the
        // one just updated in a fresh transaction above — without this, MustChangePasswordFilter
        // would keep redirecting back here on every request until the session naturally expires.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            principal.getUser().setMustChangePassword(false);
        }
        // Rendered directly on this page (rather than a flash-redirect to "/") so the confirmation
        // is guaranteed visible right where the user just acted, instead of depending on them
        // noticing a banner on whatever page they land on next.
        model.addAttribute("changePasswordForm", new ChangePasswordForm());
        model.addAttribute("successMessage", "Password changed successfully.");
        model.addAttribute("pageTitle", "Change Password");
        return "auth/change-password";
    }

    // ── Step-up re-auth gate — see TherapistStepUpAuthFilter's javadoc ──
    @GetMapping("/confirm-password")
    public String confirmPasswordForm(@RequestParam(required = false) String returnUrl, Model model) {
        model.addAttribute("returnUrl", SafeRedirectUtil.sanitize(returnUrl, "/therapists"));
        model.addAttribute("pageTitle", "Confirm Password");
        return "auth/confirm-password";
    }

    @PostMapping("/confirm-password")
    public String confirmPasswordSubmit(@RequestParam String password,
                                         @RequestParam(required = false) String returnUrl,
                                         HttpServletRequest request, Model model) {
        String safeReturnUrl = SafeRedirectUtil.sanitize(returnUrl, "/therapists");
        if (!userService.verifyOwnPassword(permissionService.currentUserId(), password)) {
            model.addAttribute("errorMessage", "Incorrect password.");
            model.addAttribute("returnUrl", safeReturnUrl);
            model.addAttribute("pageTitle", "Confirm Password");
            return "auth/confirm-password";
        }
        request.getSession(true).setAttribute(TherapistStepUpAuthFilter.SESSION_ATTR, Instant.now());
        return "redirect:" + safeReturnUrl;
    }
}
