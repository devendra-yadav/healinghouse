package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
     * Deactivating never touches any existing appointment — line items snapshot price/therapist at
     * booking time and are fully decoupled from the live catalog. It does strip this service out of
     * any combo that bundles it (see {@link ComboService#handleServiceDeactivated}), since a combo's
     * price is always live-computed and a combo can't keep offering an item that's no longer bookable.
     */
    public ComboService.CatalogItemRemovalResult deactivate(Long id) {
        ClinicService service = getById(id);
        service.setActive(false);
        clinicServiceRepository.save(service);
        log.info("Deactivated service id={} name='{}'", service.getId(), service.getName());
        return comboService.handleServiceDeactivated(id);
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
        clinicServiceRepository.delete(service);
        log.info("Permanently deleted service id={} name='{}'", id, service.getName());
    }
}