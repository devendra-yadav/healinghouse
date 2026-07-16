package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PatientPackageProductItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PatientPackageProductItemRepository extends JpaRepository<PatientPackageProductItem, Long> {

    /** Mirrors PatientPackageServiceItemRepository.findEligibleForPatient for products. */
    @Query("SELECT i FROM PatientPackageProductItem i " +
           "WHERE i.patientPackage.patient.id = :patientId " +
           "AND i.patientPackage.status = 'ACTIVE' " +
           "AND (i.patientPackage.expiryDate IS NULL OR i.patientPackage.expiryDate >= :today) " +
           "AND i.sessionsUsed < i.sessionsTotal " +
           "ORDER BY i.patientPackage.purchasedAt ASC")
    List<PatientPackageProductItem> findEligibleForPatient(@Param("patientId") Long patientId, @Param("today") LocalDate today);
}
