package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.WalletRefundForm;
import com.clinic.healinghouse.dto.WalletTopUpForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AppointmentService;
import com.clinic.healinghouse.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/patients/{patientId}/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final AppointmentService appointmentService;
    private final PermissionService permissionService;

    // THERAPIST role has no dedicated Wallet page — "Paid from Wallet" is only ever shown embedded
    // in an appointment detail page they're already authorized to view. This JSON endpoint is a
    // separate direct-URL surface though, so it needs its own ownership check
    // (requirements/Security_RBAC_Requirements_v1.md §7).
    @RequiresPermission(module = Module.WALLET, action = PermissionAction.VIEW)
    @GetMapping("/balance")
    @ResponseBody
    public BigDecimal balance(@PathVariable Long patientId) {
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId != null && !appointmentService.hasAnyAppointmentForPatientAndTherapist(patientId, ownTherapistId)) {
            throw new AccessDeniedException("You don't have access to this patient's wallet.");
        }
        return walletService.getBalance(patientId);
    }

    @RequiresPermission(module = Module.WALLET, action = PermissionAction.CREATE)
    @PostMapping("/topup")
    public String topUp(@PathVariable Long patientId, WalletTopUpForm form, RedirectAttributes ra) {
        try {
            walletService.topUp(patientId, form.getAmount(), parsePaymentMethod(form.getPaymentMethod()), form.getNote());
            ra.addFlashAttribute("successMessage", "Wallet topped up successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + returnUrlOrDefault(form.getReturnUrl(), patientId);
    }

    @RequiresPermission(module = Module.WALLET, action = PermissionAction.APPROVE)
    @PostMapping("/refund")
    public String refund(@PathVariable Long patientId, WalletRefundForm form, RedirectAttributes ra) {
        try {
            walletService.refund(patientId, form.getAmount(), parsePaymentMethod(form.getPaymentMethod()), form.getNote());
            ra.addFlashAttribute("successMessage", "Wallet balance refunded.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + returnUrlOrDefault(form.getReturnUrl(), patientId);
    }

    private PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Payment method is required.");
        }
        try {
            return PaymentMethod.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown payment method: " + raw);
        }
    }

    private String returnUrlOrDefault(String returnUrl, Long patientId) {
        return (returnUrl == null || returnUrl.isBlank()) ? "/patients/" + patientId : returnUrl;
    }
}
