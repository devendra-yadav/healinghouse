package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.repository.PatientRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
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

    public Patient save(Patient patient) {
        return patientRepository.save(patient);
    }

    public void deactivate(Long id) {
        Patient patient = getById(id);
        patient.setActive(false);
        patientRepository.save(patient);
    }
}