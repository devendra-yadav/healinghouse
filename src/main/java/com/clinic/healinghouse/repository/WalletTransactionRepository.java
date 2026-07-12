package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByPatientIdOrderByCreatedAtDesc(Long patientId, Pageable pageable);
}
