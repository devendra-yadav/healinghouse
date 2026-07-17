package com.clinic.healinghouse.controller;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.PatientSuggestionDTO;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Gender;
import com.clinic.healinghouse.entity.Module;
import com.clinic.healinghouse.entity.PermissionAction;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.security.PermissionService;
import com.clinic.healinghouse.security.RequiresPermission;
import com.clinic.healinghouse.service.AppointmentService;
import com.clinic.healinghouse.service.PackageService;
import com.clinic.healinghouse.service.PackageTemplateService;
import com.clinic.healinghouse.service.PatientHistoryService;
import com.clinic.healinghouse.service.PatientService;
import com.clinic.healinghouse.service.TherapistService;
import com.clinic.healinghouse.service.WalletService;
import com.clinic.healinghouse.util.PaginationUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService        patientService;
    private final AppointmentService    appointmentService;
    private final TherapistService      therapistService;
    private final PatientHistoryService patientHistoryService;
    private final WalletService         walletService;
    private final PackageService        packageService;
    private final PackageTemplateService packageTemplateService;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository     productRepository;
    private final HealingHouseProperties properties;
    private final PaginationUtil        paginationUtil;
    private final PermissionService     permissionService;

    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.VIEW)
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "false") boolean showInactive,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {
        // THERAPIST role: no standalone Patients list — a therapist reaches a patient only via
        // one of their own appointments (requirements/Security_RBAC_Requirements_v1.md §7).
        if (permissionService.currentTherapistId() != null) {
            throw new AccessDeniedException("The patients list isn't available for your role.");
        }
        int pageSize = paginationUtil.clampPageSize(size);
        page = paginationUtil.clampPage(page);
        model.addAttribute("patients", showInactive
                ? patientService.findAllIncludingInactive(PageRequest.of(page, pageSize, Sort.by("fullName")))
                : patientService.search(q, PageRequest.of(page, pageSize)));
        model.addAttribute("q", q);
        model.addAttribute("showInactive", showInactive);
        model.addAttribute("pageTitle", "Patients");
        return "patients/list";
    }

    /** JSON autocomplete endpoint backing the name/phone search box on the patients list. */
    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.VIEW)
    @GetMapping("/search")
    @ResponseBody
    public List<PatientSuggestionDTO> search(@RequestParam(required = false) String q) {
        if (permissionService.currentTherapistId() != null) {
            throw new AccessDeniedException("Patient search isn't available for your role.");
        }
        if (q == null || q.isBlank()) return List.of();
        return patientService.search(q).stream()
                .limit(properties.getAutocomplete().getPatientMaxSuggestions())
                .map(p -> new PatientSuggestionDTO(p.getId(), p.getFullName(), p.getPhone()))
                .toList();
    }

    // ── Detail (profile + summary stats + filterable appointment history) ──
    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.VIEW)
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) Long therapistId,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                         @RequestParam(defaultValue = "0") int walletPage,
                         @RequestParam(defaultValue = "0") int packagePage,
                         @RequestParam(defaultValue = "0") int packageListPage,
                         @RequestParam(defaultValue = "0") int page,
                         Model model, RedirectAttributes ra) {
        try {
            Patient patient = patientService.getById(id);
            // THERAPIST role: reachable only via a patient tied to one of the therapist's own
            // appointments — treated as "not found" rather than a distinct denial message so a
            // direct-URL guess at another patient's id can't be used to confirm it exists
            // (requirements/Security_RBAC_Requirements_v1.md §7).
            Long ownTherapistId = permissionService.currentTherapistId();
            if (ownTherapistId != null && !appointmentService.hasAnyAppointmentForPatientAndTherapist(id, ownTherapistId)) {
                throw new IllegalArgumentException("Patient not found");
            }
            walletPage = paginationUtil.clampPage(walletPage);
            packagePage = paginationUtil.clampPage(packagePage);
            packageListPage = paginationUtil.clampPage(packageListPage);
            page = paginationUtil.clampPage(page);

            AppointmentStatus statusEnum = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusEnum = AppointmentStatus.valueOf(status.trim());
                } catch (IllegalArgumentException ignored) {}
            }

            model.addAttribute("patient", patient);
            model.addAttribute("summary", patientHistoryService.summarize(patient));
            model.addAttribute("appointments",
                    appointmentService.findByFilters(statusEnum, therapistId, dateFrom, dateTo, null, id,
                            PageRequest.of(page, 10, Sort.by(Sort.Direction.DESC, "appointmentDateTime"))));
            model.addAttribute("walletBalance", walletService.getBalance(id));
            model.addAttribute("walletTransactions", walletService.getTransactionHistory(id,
                    PageRequest.of(walletPage, 10, Sort.by(Sort.Direction.DESC, "createdAt"))));
            model.addAttribute("patientPackages", packageService.getAllForPatient(id,
                    PageRequest.of(packageListPage, 10, Sort.by(Sort.Direction.DESC, "purchasedAt"))));
            model.addAttribute("packageTransactions", packageService.getTransactionHistoryForPatient(id,
                    PageRequest.of(packagePage, 10, Sort.by(Sort.Direction.DESC, "createdAt"))));
            model.addAttribute("activePackageTemplates", packageTemplateService.findAllActive().stream()
                    .map(packageTemplateService::toDetailDTO).toList());
            model.addAttribute("allActiveServices", clinicServiceRepository.findByActiveTrueOrderByNameAsc());
            model.addAttribute("allActiveProducts", productRepository.findByActiveTrueOrderByNameAsc());
            model.addAttribute("therapists",      therapistService.findAll());
            model.addAttribute("statuses",        AppointmentStatus.values());
            model.addAttribute("selectedStatus",  status);
            model.addAttribute("selectedTherapistId", therapistId);
            model.addAttribute("selectedDateFrom", dateFrom);
            model.addAttribute("selectedDateTo",   dateTo);
            model.addAttribute("pageTitle", "Patient — " + patient.getFullName());
            return "patients/detail";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Could not load patient: " + (e.getMessage() != null ? e.getMessage() : "not found"));
            return "redirect:/patients";
        }
    }

    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.CREATE)
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("patient", Patient.builder().build());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("pageTitle", "New Patient");
        return "patients/form";
    }

    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.EDIT)
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("patient", patientService.getById(id));
        model.addAttribute("genders", Gender.values());
        model.addAttribute("pageTitle", "Edit Patient");
        return "patients/form";
    }

    // Shared create+edit handler — can't express "CREATE if new, EDIT if existing" with one
    // @RequiresPermission, so the check is inline based on whether an id was submitted.
    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("patient") Patient patient,
                       BindingResult result,
                       Model model,
                       RedirectAttributes ra) {
        permissionService.require(Module.PATIENTS, patient.getId() == null ? PermissionAction.CREATE : PermissionAction.EDIT);
        if (result.hasErrors()) {
            model.addAttribute("genders", Gender.values());
            model.addAttribute("pageTitle", patient.getId() == null ? "New Patient" : "Edit Patient");
            return "patients/form";
        }
        try {
            patientService.save(patient);
        } catch (DataIntegrityViolationException e) {
            result.rejectValue("phone", "duplicate", "This phone number is already registered.");
            model.addAttribute("genders", Gender.values());
            model.addAttribute("pageTitle", patient.getId() == null ? "New Patient" : "Edit Patient");
            return "patients/form";
        }
        ra.addFlashAttribute("successMessage", "Patient saved successfully.");
        return "redirect:/patients";
    }

    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        patientService.deactivate(id);
        ra.addFlashAttribute("successMessage", "Patient deactivated successfully.");
        return "redirect:/patients";
    }

    @RequiresPermission(module = Module.PATIENTS, action = PermissionAction.DELETE)
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, RedirectAttributes ra) {
        patientService.activate(id);
        ra.addFlashAttribute("successMessage", "Patient reactivated successfully.");
        return "redirect:/patients/" + id;
    }
}