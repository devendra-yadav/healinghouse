package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.TherapistRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TherapistService {

    private final TherapistRepository therapistRepository;

    @Transactional(readOnly = true)
    public List<Therapist> findAll() {
        return therapistRepository.findByActiveTrueOrderByFullNameAsc();
    }

    @Transactional(readOnly = true)
    public Therapist getById(Long id) {
        return therapistRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Therapist not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Therapist> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return therapistRepository.findByFullNameContainingIgnoreCaseAndActiveTrue(query.trim());
    }

    public Therapist save(Therapist therapist) {
        return therapistRepository.save(therapist);
    }

    public void deactivate(Long id) {
        Therapist therapist = getById(id);
        therapist.setActive(false);
        therapistRepository.save(therapist);
    }
}