package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Therapist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TherapistRepository extends JpaRepository<Therapist, Long> {

    List<Therapist> findByActiveTrueOrderByFullNameAsc();

    Page<Therapist> findByActiveTrueOrderByFullNameAsc(Pageable pageable);

    List<Therapist> findByFullNameContainingIgnoreCaseAndActiveTrue(String name);

    // Active-agnostic — list-page search always matches active AND inactive (Bug_Report_v6.md Finding 16).
    Page<Therapist> findByFullNameContainingIgnoreCase(String fullName, Pageable pageable);

    long countByActiveTrue();
}