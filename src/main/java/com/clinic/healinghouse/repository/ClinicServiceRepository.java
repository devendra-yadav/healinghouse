package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.ClinicService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClinicServiceRepository extends JpaRepository<ClinicService, Long> {

    List<ClinicService> findByActiveTrueOrderByNameAsc();

    List<ClinicService> findByCategoryAndActiveTrueOrderByNameAsc(String category);

    List<ClinicService> findByNameContainingIgnoreCaseAndActiveTrue(String name);
}