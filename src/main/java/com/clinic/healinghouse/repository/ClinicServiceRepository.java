package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.ClinicService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClinicServiceRepository extends JpaRepository<ClinicService, Long> {

    List<ClinicService> findByActiveTrueOrderByNameAsc();

    Page<ClinicService> findByActiveTrueOrderByNameAsc(Pageable pageable);

    List<ClinicService> findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(String tagName);

    Page<ClinicService> findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(String tagName, Pageable pageable);

    List<ClinicService> findByNameContainingIgnoreCaseAndActiveTrue(String name);

    Page<ClinicService> findByNameContainingIgnoreCaseAndActiveTrue(String name, Pageable pageable);

    List<ClinicService> findByTagsId(Long tagId);

    long countByTagsId(Long tagId);
}