package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PackageTransaction;
import com.clinic.healinghouse.entity.PackageTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PackageTransactionRepository extends JpaRepository<PackageTransaction, Long> {

    Page<PackageTransaction> findByPatientPackageIdOrderByCreatedAtDesc(Long patientPackageId, Pageable pageable);

    Page<PackageTransaction> findByPatientPackage_Patient_IdOrderByCreatedAtDesc(Long patientId, Pageable pageable);

    List<PackageTransaction> findByTypeInAndCreatedAtBetween(List<PackageTransactionType> types, LocalDateTime start, LocalDateTime end);

    /** The sale-time record for a package — backs the package invoice's payment-method line. */
    java.util.Optional<PackageTransaction> findFirstByPatientPackageIdAndTypeOrderByCreatedAtAsc(
            Long patientPackageId, PackageTransactionType type);
}
