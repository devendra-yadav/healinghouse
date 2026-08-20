package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.PackageAvailabilityDTO;
import com.clinic.healinghouse.dto.PackageRefundForm;
import com.clinic.healinghouse.dto.PackageSaleForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PackageTransaction;
import com.clinic.healinghouse.entity.PatientPackage;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.PackageService;
import com.clinic.healinghouse.util.InvoicePdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/patients/{patientId}/packages")
@RequiredArgsConstructor
public class PackageController {

    private final PackageService packageService;
    private final InvoicePdfService invoicePdfService;

    @RequiresPermission(module = Module.PATIENT_PACKAGES, action = PermissionAction.CREATE)
    @PostMapping
    public String sell(@PathVariable Long patientId, PackageSaleForm form, RedirectAttributes ra) {
        form.setPatientId(patientId);
        try {
            packageService.sellPackage(form);
            ra.addFlashAttribute("successMessage", "Package sold successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/patients/" + patientId;
    }

    @RequiresPermission(module = Module.PATIENT_PACKAGES, action = PermissionAction.APPROVE)
    @PostMapping("/{packageId}/refund")
    public String refund(@PathVariable Long patientId, @PathVariable Long packageId,
                         PackageRefundForm form, RedirectAttributes ra) {
        try {
            packageService.refund(patientId, packageId, form.getAmount(), parsePaymentMethod(form.getPaymentMethod()), form.getNote());
            ra.addFlashAttribute("successMessage", "Package refunded successfully.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/patients/" + patientId;
    }

    /** Package sale invoice — always available (a package is paid in full at sale, unlike an
     *  appointment which may still be SCHEDULED/unpriced), gated the same VIEW permission the
     *  patient detail page's Packages card already requires. */
    @RequiresPermission(module = Module.PATIENT_PACKAGES, action = PermissionAction.VIEW)
    @GetMapping("/{packageId}/invoice/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long patientId, @PathVariable Long packageId) {
        PatientPackage pkg = packageService.getById(packageId);
        if (!pkg.getPatient().getId().equals(patientId)) {
            throw new IllegalArgumentException("This package does not belong to the specified patient.");
        }
        PackageTransaction purchase = packageService.getPurchaseTransaction(packageId);
        byte[] pdf = invoicePdfService.renderPackageInvoice(pkg, purchase.getPaymentMethod());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=invoice-package-" + packageId + ".pdf")
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .body(pdf);
    }

    /** JSON endpoint backing the appointment form's "Already Paid" section. */
    @RequiresPermission(module = Module.PATIENT_PACKAGES, action = PermissionAction.VIEW)
    @GetMapping("/available")
    @ResponseBody
    public List<PackageAvailabilityDTO> available(@PathVariable Long patientId) {
        return packageService.getPooledAvailability(patientId);
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
}
