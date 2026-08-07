package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.ContractStatus;
import com.clinic.healinghouse.entity.EmploymentContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, Long> {

    // Finds a therapist's APPROVED contract (renew-eligibility / detail card) or in-progress
    // DRAFT (reopen-existing-draft, §5.2) — no DB-level uniqueness is enforced on this pair,
    // that invariant is a service-layer guarantee (requirements §5.6).
    List<EmploymentContract> findByTherapist_IdAndStatus(Long therapistId, ContractStatus status);

    List<EmploymentContract> findByTherapist_IdOrderByCreatedAtDesc(Long therapistId);

    @Query("SELECT COUNT(c) FROM EmploymentContract c WHERE c.createdAt >= :yearStart AND c.createdAt < :yearEnd")
    long countCreatedBetween(@Param("yearStart") LocalDateTime yearStart, @Param("yearEnd") LocalDateTime yearEnd);
}
