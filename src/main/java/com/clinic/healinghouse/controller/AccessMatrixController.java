package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.RolePermission;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AccessMatrixService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /admin/access-matrix} editor (requirements/Security_RBAC_Requirements_v1.md §8.2). VIEW is
 * granted to both OWNER and ADMIN by the seeded defaults, EDIT to OWNER only — the save endpoint's
 * {@code @RequiresPermission(..., EDIT)} is what actually enforces "ADMIN gets read-only", not just
 * the template disabling its checkboxes.
 */
@Controller
@RequestMapping("/admin/access-matrix")
@RequiredArgsConstructor
public class AccessMatrixController {

    private final AccessMatrixService accessMatrixService;

    @RequiresPermission(module = Module.ACCESS_MATRIX, action = PermissionAction.VIEW)
    @GetMapping
    public String index(Model model) {
        List<RolePermission> all = accessMatrixService.findAll();
        Map<String, RolePermission> permByKey = new HashMap<>();
        for (RolePermission rp : all) {
            permByKey.put(key(rp.getRole(), rp.getModule(), rp.getAction()), rp);
        }
        model.addAttribute("permByKey", permByKey);
        model.addAttribute("roles", AppRole.values());
        model.addAttribute("modules", Module.values());
        model.addAttribute("actions", PermissionAction.values());
        model.addAttribute("pageTitle", "Access Matrix");
        return "admin/access-matrix";
    }

    @RequiresPermission(module = Module.ACCESS_MATRIX, action = PermissionAction.EDIT)
    @PostMapping("/save")
    public String save(@RequestParam(required = false) Set<Long> grantedIds, RedirectAttributes ra) {
        accessMatrixService.save(grantedIds != null ? grantedIds : Set.of());
        ra.addFlashAttribute("successMessage", "Access matrix saved — changes apply immediately.");
        return "redirect:/admin/access-matrix";
    }

    private static String key(AppRole role, Module module, PermissionAction action) {
        return role.name() + "|" + module.name() + "|" + action.name();
    }
}
