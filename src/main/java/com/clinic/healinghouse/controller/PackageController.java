package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.PackageAvailabilityDTO;
import com.clinic.healinghouse.dto.PackageRefundForm;
import com.clinic.healinghouse.dto.PackageSaleForm;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AppointmentService;
import com.clinic.healinghouse.service.PackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/patients/{patientId}/packages")
@RequiredArgsConstructor
public class PackageController {

    private final PackageService packageService;
    private final AppointmentService appointmentService;
    private final PermissionService permissionService;

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

    /** JSON endpoint backing the appointment form's "Already Paid" section. */
    @RequiresPermission(module = Module.PATIENT_PACKAGES, action = PermissionAction.VIEW)
    @GetMapping("/available")
    @ResponseBody
    public List<PackageAvailabilityDTO> available(@PathVariable Long patientId) {
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId != null && !appointmentService.hasAnyAppointmentForPatientAndTherapist(patientId, ownTherapistId)) {
            throw new AccessDeniedException("You don't have access to this patient's packages.");
        }
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
