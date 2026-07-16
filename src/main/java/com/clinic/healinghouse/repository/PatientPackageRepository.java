package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PatientPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientPackageRepository extends JpaRepository<PatientPackage, Long> {

    List<PatientPackage> findByPatientIdOrderByPurchasedAtDesc(Long patientId);

    // ── Two separate queries to avoid MultipleBagFetchException (PatientPackage has two
    //    @OneToMany bags — same trap Combo/Appointment already work around) ──────────
    @Query("SELECT DISTINCT p FROM PatientPackage p " +
           "LEFT JOIN FETCH p.serviceItems si " +
           "LEFT JOIN FETCH si.service " +
           "WHERE p.id = :id")
    Optional<PatientPackage> findWithServiceItemsById(@Param("id") Long id);

    @Query("SELECT DISTINCT p FROM PatientPackage p " +
           "LEFT JOIN FETCH p.productItems pi " +
           "LEFT JOIN FETCH pi.product " +
           "WHERE p.id = :id")
    Optional<PatientPackage> findWithProductItemsById(@Param("id") Long id);
}
