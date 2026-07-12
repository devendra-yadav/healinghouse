package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.WalletRefundForm;
import com.clinic.healinghouse.dto.WalletTopUpForm;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/patients/{patientId}/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/balance")
    @ResponseBody
    public BigDecimal balance(@PathVariable Long patientId) {
        return walletService.getBalance(patientId);
    }

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
