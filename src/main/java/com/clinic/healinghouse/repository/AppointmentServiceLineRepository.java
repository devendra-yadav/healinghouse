package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO;
import com.clinic.healinghouse.dto.ServiceTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.dto.TagTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TherapistRevenueDTO;
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

    // Commission-eligible services revenue for a therapist in a date range (completed appts only).
    // Scoped to the line's own therapist (who actually performed it), not the
    // appointment's main therapist — a single appointment can have lines performed
    // by different therapists. Only lines whose service carries the given tag
    // (e.g. "Commission") count towards payout.
    @Query("SELECT COALESCE(SUM(sl.priceAtTime * sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl JOIN sl.service.tags t " +
           "WHERE sl.therapist = :therapist " +
           "AND LOWER(t.name) = LOWER(:tagName) " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumServiceRevenueByTherapistAndDateRangeAndTag(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end,
            @Param("tagName")   String tagName);

    // Count of individual services performed that carry the given tag (e.g. "Bonus"),
    // for the performance-bonus threshold check.
    @Query("SELECT COALESCE(SUM(sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl JOIN sl.service.tags t " +
           "WHERE sl.therapist = :therapist " +
           "AND LOWER(t.name) = LOWER(:tagName) " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    long countServicesPerformedByTherapistAndDateRangeAndTag(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end,
            @Param("tagName")   String tagName);

    // All services revenue for a therapist in a date range, regardless of tag — reporting only.
    @Query("SELECT COALESCE(SUM(sl.priceAtTime * sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.therapist = :therapist " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumAllServiceRevenueByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);

    // Count of all services performed by a therapist in a date range, regardless of tag — reporting only.
    @Query("SELECT COALESCE(SUM(sl.quantity), 0) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.therapist = :therapist " +
           "AND sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end")
    long countAllServicesPerformedByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);

    // Services revenue grouped by tag (dashboard tag breakdown / performance report).
    // A service tagged with multiple tags contributes its full line total to each tag.
    @Query("SELECT new com.clinic.healinghouse.dto.TagRevenueDTO(t.name, COALESCE(SUM(sl.priceAtTime * sl.quantity), 0)) " +
           "FROM AppointmentServiceLine sl JOIN sl.service.tags t " +
           "WHERE sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY t.name")
    List<TagRevenueDTO> sumServiceRevenueByTagAndDateRange(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // Per-service bookings count and revenue (performance report).
    @Query("SELECT new com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO(" +
           "    sl.service.id, sl.service.name, COUNT(sl), COALESCE(SUM(sl.priceAtTime * sl.quantity), 0)) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY sl.service.id, sl.service.name")
    List<ServiceRevenueSummaryDTO> sumServiceRevenueByServiceAndDateRange(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // Per-service, per-therapist revenue — used to find each service's top-revenue therapist.
    @Query("SELECT new com.clinic.healinghouse.dto.ServiceTherapistRevenueDTO(" +
           "    sl.service.name, sl.therapist.fullName, COALESCE(SUM(sl.priceAtTime * sl.quantity), 0)) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY sl.service.name, sl.therapist.fullName")
    List<ServiceTherapistRevenueDTO> sumServiceRevenueByServiceAndTherapist(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // Services revenue grouped by tag x therapist (performance report cross-tab).
    @Query("SELECT new com.clinic.healinghouse.dto.TagTherapistRevenueDTO(" +
           "    t.name, sl.therapist.fullName, COALESCE(SUM(sl.priceAtTime * sl.quantity), 0)) " +
           "FROM AppointmentServiceLine sl JOIN sl.service.tags t " +
           "WHERE sl.appointment.status = 'COMPLETED' " +
           "AND sl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY t.name, sl.therapist.fullName")
    List<TagTherapistRevenueDTO> sumServiceRevenueByTagAndTherapist(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // --- Actual Revenue report (post-discount, scoped to an already-filtered appointment id set) ---

    // Per-service bookings count and post-discount (effective) revenue, scoped to a specific appointment id set
    // rather than a date range — every other report filter (therapist/patient/payment method/etc.) is already
    // baked into that id set by the caller.
    @Query("SELECT new com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO(" +
           "    sl.service.id, sl.service.name, COUNT(sl), " +
           "    COALESCE(SUM(COALESCE(sl.discountedLineTotal, sl.priceAtTime * sl.quantity)), 0)) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.appointment.id IN :appointmentIds " +
           "GROUP BY sl.service.id, sl.service.name")
    List<ServiceRevenueSummaryDTO> sumEffectiveServiceRevenueByServiceInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);

    // Raw (pre-discount) services revenue per line-level therapist, scoped to an appointment id set.
    @Query("SELECT new com.clinic.healinghouse.dto.TherapistRevenueDTO(" +
           "    sl.therapist.fullName, COALESCE(SUM(sl.priceAtTime * sl.quantity), 0)) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.appointment.id IN :appointmentIds " +
           "GROUP BY sl.therapist.fullName")
    List<TherapistRevenueDTO> sumRawServiceRevenueByTherapistInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);

    // Post-discount (effective) services revenue per line-level therapist, scoped to an appointment id set.
    @Query("SELECT new com.clinic.healinghouse.dto.TherapistRevenueDTO(" +
           "    sl.therapist.fullName, COALESCE(SUM(COALESCE(sl.discountedLineTotal, sl.priceAtTime * sl.quantity)), 0)) " +
           "FROM AppointmentServiceLine sl " +
           "WHERE sl.appointment.id IN :appointmentIds " +
           "GROUP BY sl.therapist.fullName")
    List<TherapistRevenueDTO> sumEffectiveServiceRevenueByTherapistInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);
}