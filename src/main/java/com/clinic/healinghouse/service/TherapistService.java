package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.TherapistRepository;
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
public class TherapistService {

    private final TherapistRepository therapistRepository;
    private final UserService userService;

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

    /** Paginated variant, used by the therapists list page. */
    @Transactional(readOnly = true)
    public Page<Therapist> findAll(Pageable pageable) {
        return therapistRepository.findByActiveTrueOrderByFullNameAsc(pageable);
    }

    /** Includes deactivated therapists too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<Therapist> findAllIncludingInactive(Pageable pageable) {
        return therapistRepository.findAll(pageable);
    }

    public Therapist save(Therapist therapist) {
        boolean isNew = therapist.getId() == null;
        Therapist saved = therapistRepository.save(therapist);
        log.info("{} therapist id={} name='{}'", isNew ? "Created" : "Updated", saved.getId(), saved.getFullName());
        return saved;
    }

    public void deactivate(Long id) {
        Therapist therapist = getById(id);
        therapist.setActive(false);
        therapistRepository.save(therapist);
        // Bug_Report_v5.md #1: a former therapist must lose system access along with their roster
        // slot, not just disappear from pickers while their login keeps working.
        userService.disableLinkedToTherapist(id);
        log.info("Deactivated therapist id={} name='{}'", therapist.getId(), therapist.getFullName());
    }

    public void activate(Long id) {
        Therapist therapist = getById(id);
        therapist.setActive(true);
        therapistRepository.save(therapist);
        log.info("Reactivated therapist id={} name='{}'", therapist.getId(), therapist.getFullName());
    }
}