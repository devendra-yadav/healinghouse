package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
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

    /** Paginated variant, used by the services list page; tag filter takes precedence over search. */
    @Transactional(readOnly = true)
    public Page<ClinicService> search(String query, String tagName, Pageable pageable) {
        if (StringUtils.hasText(tagName)) {
            return clinicServiceRepository.findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(tagName, pageable);
        }
        if (StringUtils.hasText(query)) {
            return clinicServiceRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim(), pageable);
        }
        return clinicServiceRepository.findByActiveTrueOrderByNameAsc(pageable);
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

    public void deactivate(Long id) {
        ClinicService service = getById(id);
        service.setActive(false);
        clinicServiceRepository.save(service);
        log.info("Deactivated service id={} name='{}'", service.getId(), service.getName());
    }
}