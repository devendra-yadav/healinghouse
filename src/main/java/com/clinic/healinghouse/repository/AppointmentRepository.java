package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.dto.PatientFirstVisitDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.Therapist;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository
        extends JpaRepository<Appointment, Long>, JpaSpecificationExecutor<Appointment> {

    // ── Detail view: two separate queries to avoid MultipleBagFetchException ────
    // Both run in the same Hibernate session; the second merges productLines into
    // the L1-cached Appointment entity returned by the first.
    @Query("SELECT DISTINCT a FROM Appointment a " +
           "LEFT JOIN FETCH a.patient " +
           "LEFT JOIN FETCH a.therapist " +
           "LEFT JOIN FETCH a.serviceLines sl " +
           "LEFT JOIN FETCH sl.service " +
           "WHERE a.id = :id")
    Optional<Appointment> findWithServiceLinesById(@Param("id") Long id);

    @Query("SELECT DISTINCT a FROM Appointment a " +
           "LEFT JOIN FETCH a.productLines pl " +
           "LEFT JOIN FETCH pl.product " +
           "WHERE a.id = :id")
    Optional<Appointment> findWithProductLinesById(@Param("id") Long id);

    @Query("SELECT DISTINCT a FROM Appointment a " +
           "LEFT JOIN FETCH a.combos ac " +
           "LEFT JOIN FETCH ac.combo " +
           "WHERE a.id = :id")
    Optional<Appointment> findWithCombosById(@Param("id") Long id);

    /**
     * Forces the parent Appointment's @Version to bump even though none of its own fields change —
     * used by per-line therapist reassignment (which saves the AppointmentServiceLine/ProductLine row
     * directly and would otherwise never touch the aggregate root's version) so a concurrent
     * updateAppointment loaded before this commit fails its own optimistic-lock check instead of
     * silently clobbering the reassignment on its next clear-and-rebuild of the lines.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> lockForVersionBump(@Param("id") Long id);

    // ── Simple finders ────────────────────────────────────────────────────────
    List<Appointment> findByPatientOrderByAppointmentDateTimeDesc(Patient patient);

    List<Appointment> findByTherapistOrderByAppointmentDateTimeDesc(Therapist therapist);

    List<Appointment> findByStatusOrderByAppointmentDateTimeDesc(AppointmentStatus status);

    // ── Today's appointments (dashboard) ─────────────────────────────────────
    @Query("SELECT a FROM Appointment a " +
           "JOIN FETCH a.patient " +
           "JOIN FETCH a.therapist " +
           "WHERE a.appointmentDateTime >= :startOfDay " +
           "AND a.appointmentDateTime < :endOfDay " +
           "ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findTodayAppointments(@Param("startOfDay") LocalDateTime startOfDay,
                                             @Param("endOfDay")   LocalDateTime endOfDay);

    // ── Date-range queries (reports) ──────────────────────────────────────────
    @Query("SELECT a FROM Appointment a " +
           "WHERE a.appointmentDateTime BETWEEN :start AND :end " +
           "ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByDateRange(@Param("start") LocalDateTime start,
                                      @Param("end")   LocalDateTime end);

    @Query("SELECT a FROM Appointment a " +
           "WHERE a.therapist = :therapist " +
           "AND a.appointmentDateTime BETWEEN :start AND :end " +
           "ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByTherapistAndDateRange(@Param("therapist") Therapist therapist,
                                                   @Param("start")    LocalDateTime start,
                                                   @Param("end")      LocalDateTime end);

    @Query("SELECT a FROM Appointment a " +
           "WHERE a.status = :status " +
           "AND a.appointmentDateTime BETWEEN :start AND :end " +
           "ORDER BY a.appointmentDateTime ASC")
    List<Appointment> findByStatusAndDateRange(@Param("status") AppointmentStatus status,
                                                @Param("start") LocalDateTime start,
                                                @Param("end")   LocalDateTime end);

    // ── Count helpers (dashboard KPIs) ────────────────────────────────────────
    long countByStatus(AppointmentStatus status);

    /** Half-open [start, end) count, matching findTodayAppointments' boundary semantics exactly. */
    long countByAppointmentDateTimeGreaterThanEqualAndAppointmentDateTimeLessThan(LocalDateTime start, LocalDateTime end);

    // ── Revenue aggregate (completed appointments in a date range) ─────────────
    @Query("SELECT COALESCE(SUM(a.grandTotal), 0) FROM Appointment a " +
           "WHERE a.status = 'COMPLETED' " +
           "AND a.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumRevenueByDateRange(@Param("start") LocalDateTime start,
                                     @Param("end")   LocalDateTime end);

    // ── Patient acquisition report ────────────────────────────────────────────
    // Earliest-ever appointment date (any status) for every patient with at least
    // one appointment in the range — used to classify new vs. repeat patients.
    @Query("SELECT new com.clinic.healinghouse.dto.PatientFirstVisitDTO(a.patient.id, MIN(a.appointmentDateTime)) " +
           "FROM Appointment a " +
           "WHERE a.patient.id IN (" +
           "    SELECT DISTINCT a2.patient.id FROM Appointment a2 WHERE a2.appointmentDateTime BETWEEN :start AND :end" +
           ") " +
           "GROUP BY a.patient.id")
    List<PatientFirstVisitDTO> findFirstVisitDatesForPatientsActiveInRange(@Param("start") LocalDateTime start,
                                                                            @Param("end")   LocalDateTime end);
}