package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    List<Patient> findByActiveTrueOrderByFullNameAsc();

    Page<Patient> findByActiveTrueOrderByFullNameAsc(Pageable pageable);

    List<Patient> findByFullNameContainingIgnoreCaseAndActiveTrue(String name);

    List<Patient> findByPhoneContainingIgnoreCaseAndActiveTrue(String phone);

    Optional<Patient> findByPhone(String phone);

    @Query("SELECT p FROM Patient p WHERE p.active = true AND " +
           "(LOWER(p.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "p.phone LIKE CONCAT('%', :q, '%'))" +
           " ORDER BY p.fullName ASC")
    List<Patient> searchActive(@Param("q") String query);

    @Query("SELECT p FROM Patient p WHERE p.active = true AND " +
           "(LOWER(p.fullName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "p.phone LIKE CONCAT('%', :q, '%'))")
    Page<Patient> searchActive(@Param("q") String query, Pageable pageable);
}