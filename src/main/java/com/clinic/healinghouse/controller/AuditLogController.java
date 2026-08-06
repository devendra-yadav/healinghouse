package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.AuditAction;
import com.clinic.healinghouse.entity.AuditLog;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AuditLogService;
import com.clinic.healinghouse.util.PaginationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Read-only {@code /admin/audit-log} viewer (requirements/Security_RBAC_Requirements_v1.md §6.4) —
 * OWNER only, no CSV/PDF export (internal forensic tool, not a clinic report). The log itself is
 * populated exclusively by {@code config.AuditLogEventListener}; this controller never writes to it.
 */
@Controller
@RequestMapping("/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final HealingHouseProperties properties;
    private final PaginationUtil paginationUtil;

    @RequiresPermission(module = Module.AUDIT_LOG, action = PermissionAction.VIEW)
    @GetMapping
    public String index(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                         @RequestParam(required = false) String username,
                         @RequestParam(required = false) String entityType,
                         @RequestParam(required = false) AuditAction action,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "50") int size,
                         Model model) {
        LocalDate today = LocalDate.now();
        LocalDate from = dateFrom != null ? dateFrom : today.minusDays(properties.getReports().getDefaultRangeDays() - 1);
        LocalDate to = dateTo != null ? dateTo : today;

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX);

        int pageSize = paginationUtil.clampPageSize(size);
        int pageNumber = paginationUtil.clampPage(page);
        Page<AuditLog> entries = auditLogService.search(start, end, username, entityType, action,
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "timestamp")));

        model.addAttribute("entries", entries);
        model.addAttribute("dateFrom", from);
        model.addAttribute("dateTo", to);
        model.addAttribute("username", username);
        model.addAttribute("entityType", entityType);
        model.addAttribute("action", action);
        model.addAttribute("entityTypes", auditLogService.distinctEntityTypes());
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("pageTitle", "Audit Log");
        return "admin/audit-log";
    }
}
