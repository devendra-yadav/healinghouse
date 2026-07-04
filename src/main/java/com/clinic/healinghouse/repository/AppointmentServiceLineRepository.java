package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentServiceLine;
import com.clinic.healinghouse.entity.Therapist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentServiceLineRepository extends JpaRepository<AppointmentServiceLine, Long> {

    List<AppointmentServiceLine> findByAppointment(Appointment appointment);

    // Total services revenue for a therapist in a date range (completed appts only).
    // Scoped to the line's own therapist (who actually performed it), not the
    // appointment's main therapist — a single appointment can have lines performed
    // by different therapists.
    @Query("SELECT COALESCE(SUM(sl.priceAtTime * sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.therapist = :therapist " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumServiceRevenueByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);

    // Count of individual services performed (for bonus threshold check)
    @Query("SELECT COALESCE(SUM(sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.therapist = :therapist " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    long countServicesPerformedByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);
}