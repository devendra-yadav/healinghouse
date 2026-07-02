package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Therapist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TherapistRepository extends JpaRepository<Therapist, Long> {

    List<Therapist> findByActiveTrueOrderByFullNameAsc();

    List<Therapist> findByFullNameContainingIgnoreCaseAndActiveTrue(String name);
}