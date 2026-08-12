package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.AppointmentPaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentPaymentTransactionRepository extends JpaRepository<AppointmentPaymentTransaction, Long> {

    Page<AppointmentPaymentTransaction> findByAppointment_IdOrderByCreatedAtDesc(Long appointmentId, Pageable pageable);

    List<AppointmentPaymentTransaction> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // Per-appointment idempotency check for AppointmentPaymentTransactionBackfill — see that class's
    // javadoc for why a single table-wide count() guard isn't safe here.
    boolean existsByAppointment_Id(Long appointmentId);
}
