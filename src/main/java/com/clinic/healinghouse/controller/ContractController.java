package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.dto.ContractCancelForm;
import com.clinic.healinghouse.dto.ContractContentUpdateDTO;
import com.clinic.healinghouse.dto.EmploymentContractForm;
import com.clinic.healinghouse.entity.EmploymentContract;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.ContractService;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Employment Contracts — generate/review-edit/approve/cancel/renew/delete/acknowledge
 * (requirements/Employment_Contracts_Requirements_v1.md §6.2). Every route except
 * {@code /acknowledge} is Owner-only via the {@code CONTRACTS} module grants (§5.1); THERAPIST/
 * THERAPIST_PLUS hold VIEW only, additionally scoped to their own linked therapist's contracts by
 * {@link #enforceOwnContract}, the same shape as {@code TherapistController.enforceOwnTherapist}.
 */
@Controller
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final TherapistService therapistService;
    private final UserService userService;
    private final PermissionService permissionService;

    // ── Generate (fresh, non-renewal) ──

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.CREATE)
    @GetMapping("/therapists/{therapistId}/contracts/new")
    public String newForm(@PathVariable Long therapistId, Model model) {
        Therapist therapist = therapistService.getById(therapistId);
        model.addAttribute("therapist", therapist);
        model.addAttribute("contractForm", EmploymentContractForm.fromTherapist(therapist));
        model.addAttribute("renewal", false);
        model.addAttribute("pageTitle", "Generate Contract — " + therapist.getFullName());
        return "contracts/form";
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.CREATE)
    @PostMapping("/therapists/{therapistId}/contracts")
    public String create(@PathVariable Long therapistId,
                          @ModelAttribute("contractForm") EmploymentContractForm form,
                          Model model, RedirectAttributes ra) {
        try {
            EmploymentContract draft = contractService.generateDraft(therapistId, form, currentUser());
            return "redirect:/contracts/" + draft.getId() + "/review";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/therapists/" + therapistId + "/contracts/new";
        }
    }

    // ── Renew ──

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.CREATE)
    @GetMapping("/therapists/{therapistId}/contracts/renew")
    public String renewForm(@PathVariable Long therapistId, Model model, RedirectAttributes ra) {
        try {
            Therapist therapist = therapistService.getById(therapistId);
            EmploymentContract current = contractService.getApprovedForTherapist(therapistId);
            model.addAttribute("therapist", therapist);
            model.addAttribute("contractForm", EmploymentContractForm.fromPreviousContract(current));
            model.addAttribute("renewal", true);
            model.addAttribute("previousContractId", current.getId());
            model.addAttribute("pageTitle", "Renew Contract — " + therapist.getFullName());
            return "contracts/form";
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/therapists/" + therapistId;
        }
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.CREATE)
    @PostMapping("/therapists/{therapistId}/contracts/renew")
    public String renew(@PathVariable Long therapistId,
                         @ModelAttribute("contractForm") EmploymentContractForm form,
                         @RequestParam Long previousContractId,
                         RedirectAttributes ra) {
        try {
            EmploymentContract draft = contractService.renewDraft(previousContractId, form, currentUser());
            return "redirect:/contracts/" + draft.getId() + "/review";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/therapists/" + therapistId + "/contracts/renew";
        }
    }

    // ── Review / edit (DRAFT only) ──

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.EDIT)
    @GetMapping("/contracts/{id}/review")
    public String review(@PathVariable Long id, Model model) {
        EmploymentContract contract = contractService.getById(id);
        model.addAttribute("contract", contract);
        model.addAttribute("pageTitle", "Review Contract — " + contract.getContractNumber());
        return "contracts/review";
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.EDIT)
    @PostMapping("/contracts/{id}/content")
    public String saveContent(@PathVariable Long id,
                               @ModelAttribute("content") ContractContentUpdateDTO dto,
                               RedirectAttributes ra) {
        try {
            contractService.updateContent(id, dto);
            ra.addFlashAttribute("successMessage", "Draft saved.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/contracts/" + id + "/review";
    }

    // ── Approve / Cancel / Delete / Renew-supersede ──

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.APPROVE)
    @PostMapping("/contracts/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes ra) {
        try {
            contractService.approve(id, currentUser());
            ra.addFlashAttribute("successMessage", "Contract approved.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/contracts/" + id + "/review";
        }
        return "redirect:/contracts/" + id;
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.APPROVE)
    @PostMapping("/contracts/{id}/cancel")
    public String cancel(@PathVariable Long id, @ModelAttribute("cancelForm") ContractCancelForm form, RedirectAttributes ra) {
        try {
            contractService.cancel(id, form, currentUser());
            ra.addFlashAttribute("successMessage", "Contract cancelled.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/contracts/" + id;
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.DELETE)
    @PostMapping("/contracts/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        EmploymentContract contract = contractService.getById(id);
        Long therapistId = contract.getTherapist().getId();
        try {
            contractService.delete(id);
            ra.addFlashAttribute("successMessage", "Draft contract deleted.");
        } catch (IllegalStateException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/contracts/" + id;
        }
        return "redirect:/therapists/" + therapistId;
    }

    // ── Acknowledge — self-service, not @RequiresPermission-gated (mirrors AccountController) ──

    @PostMapping("/contracts/{id}/acknowledge")
    public String acknowledge(@PathVariable Long id, RedirectAttributes ra) {
        enforceOwnContract(id);
        contractService.acknowledge(id, currentUser());
        ra.addFlashAttribute("successMessage", "Contract acknowledged.");
        return "redirect:/contracts/" + id;
    }

    // ── View ──

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.VIEW)
    @GetMapping("/contracts/{id}")
    public String detail(@PathVariable Long id, Model model) {
        enforceOwnContract(id);
        EmploymentContract contract = contractService.getById(id);
        model.addAttribute("contract", contract);
        model.addAttribute("ownTherapistId", permissionService.currentTherapistId());
        model.addAttribute("pageTitle", "Contract " + contract.getContractNumber());
        return "contracts/detail";
    }

    @RequiresPermission(module = Module.CONTRACTS, action = PermissionAction.VIEW)
    @GetMapping("/contracts/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        enforceOwnContract(id);
        EmploymentContract contract = contractService.getById(id);
        byte[] pdf = contractService.getPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + contract.getContractNumber() + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private User currentUser() {
        return userService.getById(permissionService.currentUserId());
    }

    /** THERAPIST/THERAPIST_PLUS is scoped to their own linked therapist's contracts only — mirrors
     *  TherapistController.enforceOwnTherapist. A no-op for OWNER (currentTherapistId() is null). */
    private void enforceOwnContract(Long id) {
        Long ownTherapistId = permissionService.currentTherapistId();
        if (ownTherapistId != null && !contractService.belongsToTherapist(id, ownTherapistId)) {
            throw new AccessDeniedException("You don't have access to this contract.");
        }
    }
}
