package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PatientPackageServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PatientPackageServiceItemRepository extends JpaRepository<PatientPackageServiceItem, Long> {

    /**
     * Every item this patient could still draw a session from: parent package ACTIVE, not expired
     * (or never expiring), and this item not yet fully used. Ordered oldest-purchase-first (FIFO) so
     * PackageService.getPooledAvailability can just take the first entry per service id as the
     * "next item to draw from" without a separate MIN(purchasedAt) query.
     */
    @Query("SELECT i FROM PatientPackageServiceItem i " +
           "WHERE i.patientPackage.patient.id = :patientId " +
           "AND i.patientPackage.status = 'ACTIVE' " +
           "AND (i.patientPackage.expiryDate IS NULL OR i.patientPackage.expiryDate >= :today) " +
           "AND i.sessionsUsed < i.sessionsTotal " +
           "ORDER BY i.patientPackage.purchasedAt ASC")
    List<PatientPackageServiceItem> findEligibleForPatient(@Param("patientId") Long patientId, @Param("today") LocalDate today);
}
