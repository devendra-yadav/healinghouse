package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.WalletRefundForm;
import com.clinic.healinghouse.dto.WalletTopUpForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.WalletService;
import com.clinic.healinghouse.util.SafeRedirectUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Map;

@Controller
@RequestMapping("/patients/{patientId}/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @RequiresPermission(module = Module.WALLET, action = PermissionAction.VIEW)
    @GetMapping("/balance")
    @ResponseBody
    public BigDecimal balance(@PathVariable Long patientId) {
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

    /**
     * AJAX variant of {@link #topUp}, used by the appointment form's top-up modal so an
     * in-progress booking is never disrupted by a page navigation — returns the fresh balance
     * as JSON instead of a redirect. Distinguished from the plain form-post mapping above via
     * the "ajax" request param, so patients/detail.html's real (non-AJAX) submission is unaffected.
     */
    @RequiresPermission(module = Module.WALLET, action = PermissionAction.CREATE)
    @PostMapping(value = "/topup", params = "ajax=true")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> topUpAjax(@PathVariable Long patientId, WalletTopUpForm form) {
        try {
            walletService.topUp(patientId, form.getAmount(), parsePaymentMethod(form.getPaymentMethod()), form.getNote());
            return ResponseEntity.ok(Map.of("success", true, "balance", walletService.getBalance(patientId)));
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : "Top up failed.";
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
        }
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

    /**
     * AJAX variant of {@link #refund}, used by patients/detail.html's refund modal so the
     * outcome can be shown as a toast without a full page reload — mirrors {@link #topUpAjax}.
     */
    @RequiresPermission(module = Module.WALLET, action = PermissionAction.APPROVE)
    @PostMapping(value = "/refund", params = "ajax=true")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> refundAjax(@PathVariable Long patientId, WalletRefundForm form) {
        try {
            walletService.refund(patientId, form.getAmount(), parsePaymentMethod(form.getPaymentMethod()), form.getNote());
            return ResponseEntity.ok(Map.of("success", true, "balance", walletService.getBalance(patientId)));
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : "Refund failed.";
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
        }
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
        return SafeRedirectUtil.sanitize(returnUrl, "/patients/" + patientId);
    }
}
