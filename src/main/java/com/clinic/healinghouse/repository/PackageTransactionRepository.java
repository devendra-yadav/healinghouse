package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PackageTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageTransactionRepository extends JpaRepository<PackageTransaction, Long> {

    Page<PackageTransaction> findByPatientPackageIdOrderByCreatedAtDesc(Long patientPackageId, Pageable pageable);

    Page<PackageTransaction> findByPatientPackage_Patient_IdOrderByCreatedAtDesc(Long patientId, Pageable pageable);
}
