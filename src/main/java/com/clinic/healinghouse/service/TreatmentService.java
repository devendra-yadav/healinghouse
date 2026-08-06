package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.CsvImportResultDTO;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import com.clinic.healinghouse.repository.PackageTemplateRepository;
import com.clinic.healinghouse.repository.PatientPackageServiceItemRepository;
import com.clinic.healinghouse.util.CsvImportUtil;
import com.opencsv.exceptions.CsvValidationException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manages the treatment / therapy catalog (ClinicService entities). */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TreatmentService {

    private final ClinicServiceRepository clinicServiceRepository;
    private final TagService tagService;
    private final AppointmentServiceLineRepository appointmentServiceLineRepository;
    private final ComboRepository comboRepository;
    private final ComboService comboService;
    private final PackageTemplateRepository packageTemplateRepository;
    private final PackageTemplateService packageTemplateService;
    private final PatientPackageServiceItemRepository patientPackageServiceItemRepository;
    private final CsvImportUtil csvImportUtil;

    @Transactional(readOnly = true)
    public List<ClinicService> findAll() {
        return clinicServiceRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public ClinicService getById(Long id) {
        return clinicServiceRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Service not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ClinicService> findByTag(String tagName) {
        return clinicServiceRepository.findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(tagName);
    }

    @Transactional(readOnly = true)
    public List<ClinicService> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return clinicServiceRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim());
    }

    /**
     * Paginated variant, used by the services list page; tag filter takes precedence over search.
     * Unlike the unpaginated {@link #search(String)} (used by booking-flow autocompletes, which must
     * stay active-only), a filtered list-page search always matches active AND inactive — staff need
     * to be able to find a deactivated service by name/tag without paging through the whole inactive list.
     */
    @Transactional(readOnly = true)
    public Page<ClinicService> search(String query, String tagName, Pageable pageable) {
        if (StringUtils.hasText(tagName)) {
            return clinicServiceRepository.findByTagsNameIgnoreCaseOrderByNameAsc(tagName, pageable);
        }
        if (StringUtils.hasText(query)) {
            return clinicServiceRepository.findByNameContainingIgnoreCase(query.trim(), pageable);
        }
        return clinicServiceRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    /** Includes deactivated services too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<ClinicService> findAllIncludingInactive(Pageable pageable) {
        return clinicServiceRepository.findAll(pageable);
    }

    /** tagNames are resolved via find-or-create (see {@link TagService#findOrCreate}) before saving. */
    public ClinicService save(ClinicService service, List<String> tagNames) {
        boolean isNew = service.getId() == null;
        service.setTags(resolveTags(tagNames));
        ClinicService saved = clinicServiceRepository.save(service);
        log.info("{} service id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    private Set<Tag> resolveTags(List<String> tagNames) {
        Set<Tag> tags = new HashSet<>();
        if (tagNames == null) return tags;
        for (String name : tagNames) {
            if (StringUtils.hasText(name)) tags.add(tagService.findOrCreate(name.trim()));
        }
        return tags;
    }

    /**
     * Best-effort bulk import — expects headers {@code name, description, durationMinutes, price,
     * tags, active} (case-insensitive, any order; only name and price are required). Each row is
     * validated and inserted independently: a duplicate name (case-insensitive, active or inactive)
     * is skipped, a validation failure is recorded as an error, and neither aborts the rest of the
     * file — matches how staff actually use this (a large, occasionally-messy spreadsheet).
     * {@code tags} is semicolon-separated (a comma is already the CSV column delimiter) and resolved
     * via the same {@link TagService#findOrCreate} find-or-create path the manual form uses.
     */
    public CsvImportResultDTO importFromCsv(MultipartFile file) {
        List<CsvImportUtil.CsvRow> rows;
        try {
            rows = csvImportUtil.readRows(file);
        } catch (IOException | CsvValidationException e) {
            throw new IllegalArgumentException("Could not read CSV file: " + e.getMessage());
        }

        List<CsvImportResultDTO.RowResult> results = new ArrayList<>();
        int success = 0, skipped = 0, errors = 0;
        for (CsvImportUtil.CsvRow row : rows) {
            String name = row.get("name") == null ? "" : row.get("name").trim();
            try {
                if (!StringUtils.hasText(name)) {
                    throw new IllegalArgumentException("Name is required");
                }
                if (clinicServiceRepository.existsByNameIgnoreCase(name)) {
                    results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                            CsvImportResultDTO.RowStatus.SKIPPED, "A service named \"" + name + "\" already exists"));
                    skipped++;
                    continue;
                }
                BigDecimal price = CsvImportUtil.parsePrice(row.get("price"));
                int duration = CsvImportUtil.parseOptionalNonNegativeInt(row.get("durationminutes"), 60, "durationMinutes");
                boolean active = CsvImportUtil.parseActive(row.get("active"));
                List<String> tagNames = CsvImportUtil.parseTags(row.get("tags"));
                String description = StringUtils.hasText(row.get("description")) ? row.get("description").trim() : null;

                ClinicService service = ClinicService.builder()
                        .name(name)
                        .description(description)
                        .durationMinutes(duration)
                        .price(price)
                        .active(active)
                        .build();
                save(service, tagNames);
                results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                        CsvImportResultDTO.RowStatus.SUCCESS, "Created"));
                success++;
            } catch (Exception e) {
                results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                        CsvImportResultDTO.RowStatus.ERROR, e.getMessage()));
                errors++;
            }
        }
        log.info("Service CSV import: {} rows, {} created, {} skipped, {} errors", rows.size(), success, skipped, errors);
        return new CsvImportResultDTO(rows.size(), success, skipped, errors, results);
    }

    /**
     * Deactivating never touches any existing appointment — line items snapshot price/therapist at
     * booking time and are fully decoupled from the live catalog. It does strip this service out of
     * any combo that bundles it (see {@link ComboService#handleServiceDeactivated}) and any package
     * template that bundles it (see {@link PackageTemplateService#handleServiceDeactivated},
     * Bug_Report_v6.md Finding 9), since both a combo's and a template's price are always
     * live-computed and neither can keep offering an item that's no longer bookable. Returns a
     * combined flash-message suffix describing both impacts.
     */
    public String deactivate(Long id) {
        ClinicService service = getById(id);
        service.setActive(false);
        clinicServiceRepository.save(service);
        log.info("Deactivated service id={} name='{}'", service.getId(), service.getName());
        return comboService.handleServiceDeactivated(id).describe()
                + packageTemplateService.handleServiceDeactivated(id).describe();
    }

    public void activate(Long id) {
        ClinicService service = getById(id);
        service.setActive(true);
        clinicServiceRepository.save(service);
        log.info("Reactivated service id={} name='{}'", service.getId(), service.getName());
    }

    /** Only allowed once deactivated, and only if unreferenced by appointment history or any combo definition. */
    public void permanentlyDelete(Long id) {
        ClinicService service = getById(id);
        if (service.isActive()) {
            throw new IllegalArgumentException("Deactivate this service before permanently deleting it.");
        }
        if (appointmentServiceLineRepository.existsByService_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + service.getName()
                    + "\" — it is used in one or more appointments.");
        }
        if (comboRepository.existsByServiceItems_Service_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + service.getName()
                    + "\" — it is part of one or more combos.");
        }
        // The FK from both item tables is non-nullable, so the DB would currently block this anyway —
        // but as a bare DataIntegrityViolationException caught only by GlobalExceptionHandler's generic
        // handler, giving a vague message instead of this specific, actionable one (Bug_Report_v4.md #12).
        if (packageTemplateRepository.existsByServiceItems_Service_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + service.getName()
                    + "\" — it is part of one or more package templates.");
        }
        if (patientPackageServiceItemRepository.existsByService_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + service.getName()
                    + "\" — it is part of one or more sold patient packages.");
        }
        clinicServiceRepository.delete(service);
        log.info("Permanently deleted service id={} name='{}'", id, service.getName());
    }
}