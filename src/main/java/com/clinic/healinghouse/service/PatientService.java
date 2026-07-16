package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.repository.PatientRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PatientService {

    private final PatientRepository patientRepository;

    @Transactional(readOnly = true)
    public List<Patient> findAll() {
        return patientRepository.findByActiveTrueOrderByFullNameAsc();
    }

    @Transactional(readOnly = true)
    public Patient getById(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Patient not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Patient> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return patientRepository.searchActive(query.trim());
    }

    /** Paginated variant, used by the patients list page. */
    @Transactional(readOnly = true)
    public Page<Patient> search(String query, Pageable pageable) {
        if (!StringUtils.hasText(query)) return patientRepository.findByActiveTrueOrderByFullNameAsc(pageable);
        return patientRepository.searchActive(query.trim(), pageable);
    }

    /** Includes deactivated patients too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<Patient> findAllIncludingInactive(Pageable pageable) {
        return patientRepository.findAll(pageable);
    }

    public Patient save(Patient patient) {
        boolean isNew = patient.getId() == null;
        Patient saved = patientRepository.save(patient);
        log.info("{} patient id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getFullName());
        return saved;
    }

    public void deactivate(Long id) {
        Patient patient = getById(id);
        patient.setActive(false);
        patientRepository.save(patient);
        log.info("Deactivated patient id={} name='{}'", patient.getId(), patient.getFullName());
    }

    public void activate(Long id) {
        Patient patient = getById(id);
        patient.setActive(true);
        patientRepository.save(patient);
        log.info("Reactivated patient id={} name='{}'", patient.getId(), patient.getFullName());
    }
}