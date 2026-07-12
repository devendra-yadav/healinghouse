package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PatientWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface PatientWalletRepository extends JpaRepository<PatientWallet, Long> {

    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM PatientWallet w")
    BigDecimal sumAllBalances();
}
