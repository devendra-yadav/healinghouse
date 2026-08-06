package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    @Query("select distinct a.entityType from AuditLog a order by a.entityType")
    List<String> findDistinctEntityTypes();
}
