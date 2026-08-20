package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.entity.WalletTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByPatientIdOrderByCreatedAtDesc(Long patientId, Pageable pageable);

    List<WalletTransaction> findByTypeInAndCreatedAtBetween(List<WalletTransactionType> types, LocalDateTime start, LocalDateTime end);
}
