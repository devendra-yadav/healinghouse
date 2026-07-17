package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.PasswordResetForm;
import com.clinic.healinghouse.dto.UserForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.UserService;
import com.clinic.healinghouse.util.PaginationUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * {@code /admin/users} CRUD (requirements/Security_RBAC_Requirements_v1.md §8.1). Business-rule
 * violations (duplicate username, THERAPIST-role/therapist-link mismatch, ADMIN touching an OWNER
 * account) are raised by {@link UserService} as {@code IllegalArgumentException} and re-rendered
 * inline on the form — mirrors {@code PatientController.save}'s duplicate-phone handling — rather
 * than a flash-redirect that would lose everything staff just typed.
 */
@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;
    private final PermissionService permissionService;
    private final PaginationUtil paginationUtil;

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(defaultValue = "false") boolean showInactive,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        model.addAttribute("users", userService.findAll(showInactive, PageRequest.of(page, pageSize)));
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("currentUserId", permissionService.currentUserId());
        model.addAttribute("currentRole", permissionService.currentRole());
        model.addAttribute("pageTitle", "User Management");
        return "admin/users/list";
    }

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        UserForm form = new UserForm();
        model.addAttribute("userForm", form);
        model.addAttribute("isNew", true);
        model.addAttribute("roles", AppRole.values());
        model.addAttribute("availableTherapists", userService.getAvailableTherapistsForLinking(null));
        model.addAttribute("pageTitle", "New User");
        return "admin/users/form";
    }

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User user = userService.getById(id);
        UserForm form = new UserForm();
        form.setId(user.getId());
        form.setUsername(user.getUsername());
        form.setFullName(user.getFullName());
        form.setRole(user.getRole().name());
        form.setTherapistId(user.getTherapist() != null ? user.getTherapist().getId() : null);
        model.addAttribute("userForm", form);
        model.addAttribute("isNew", false);
        model.addAttribute("roles", AppRole.values());
        model.addAttribute("availableTherapists", userService.getAvailableTherapistsForLinking(id));
        model.addAttribute("pageTitle", "Edit User — " + user.getUsername());
        return "admin/users/form";
    }

    // Shared create+edit handler — permission is CREATE if no id was submitted, EDIT otherwise
    // (same pattern as PatientController/TherapistController's "save").
    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("userForm") UserForm form,
                        BindingResult result,
                        Model model,
                        RedirectAttributes ra) {
        boolean isNew = form.getId() == null;
        permissionService.require(Module.USER_MANAGEMENT, isNew ? PermissionAction.CREATE : PermissionAction.EDIT);

        if (!result.hasErrors()) {
            try {
                if (isNew) {
                    userService.create(form);
                } else {
                    userService.update(form);
                }
                ra.addFlashAttribute("successMessage", "User saved successfully.");
                return "redirect:/admin/users";
            } catch (IllegalArgumentException e) {
                model.addAttribute("errorMessage", e.getMessage());
            }
        }
        model.addAttribute("isNew", isNew);
        model.addAttribute("roles", AppRole.values());
        model.addAttribute("availableTherapists", userService.getAvailableTherapistsForLinking(form.getId()));
        model.addAttribute("pageTitle", isNew ? "New User" : "Edit User");
        return "admin/users/form";
    }

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.DELETE)
    @PostMapping("/{id}/disable")
    public String disable(@PathVariable Long id, RedirectAttributes ra) {
        try {
            userService.disable(id);
            ra.addFlashAttribute("successMessage", "User account disabled.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.DELETE)
    @PostMapping("/{id}/enable")
    public String enable(@PathVariable Long id, RedirectAttributes ra) {
        try {
            userService.enable(id);
            ra.addFlashAttribute("successMessage", "User account re-enabled.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @RequiresPermission(module = Module.USER_MANAGEMENT, action = PermissionAction.EDIT)
    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, PasswordResetForm form, RedirectAttributes ra) {
        try {
            userService.resetPassword(id, form.getNewPassword());
            ra.addFlashAttribute("successMessage", "Password reset — the user must change it on next login.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
