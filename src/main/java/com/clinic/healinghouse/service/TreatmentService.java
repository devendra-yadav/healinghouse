package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** Manages the treatment / therapy catalog (ClinicService entities). */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TreatmentService {

    private final ClinicServiceRepository clinicServiceRepository;

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
    public List<ClinicService> findByCategory(String category) {
        return clinicServiceRepository.findByCategoryAndActiveTrueOrderByNameAsc(category);
    }

    @Transactional(readOnly = true)
    public List<ClinicService> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return clinicServiceRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim());
    }

    public ClinicService save(ClinicService service) {
        boolean isNew = service.getId() == null;
        ClinicService saved = clinicServiceRepository.save(service);
        log.info("{} service id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getName());
        return saved;
    }

    public void deactivate(Long id) {
        ClinicService service = getById(id);
        service.setActive(false);
        clinicServiceRepository.save(service);
        log.info("Deactivated service id={} name='{}'", service.getId(), service.getName());
    }
}