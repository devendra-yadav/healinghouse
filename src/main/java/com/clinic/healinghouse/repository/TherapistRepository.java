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

    long countByActiveTrue();
}