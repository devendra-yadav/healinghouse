package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.dto.ProductRevenueSummaryDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.dto.TagTherapistRevenueDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentProductLine;
import com.clinic.healinghouse.entity.Therapist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentProductLineRepository extends JpaRepository<AppointmentProductLine, Long> {

    List<AppointmentProductLine> findByAppointment(Appointment appointment);

    // Commission-eligible products revenue for a therapist in a date range (completed appts only).
    // Scoped to the line's own therapist (who actually sold/administered it), not the
    // appointment's main therapist. Only lines whose product carries the given tag
    // (e.g. "Commission") count towards payout.
    @Query("SELECT COALESCE(SUM(pl.lineTotal), 0) " +
           "FROM AppointmentProductLine pl JOIN pl.product.tags t " +
           "WHERE pl.therapist = :therapist " +
           "AND LOWER(t.name) = LOWER(:tagName) " +
           "AND pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumProductRevenueByTherapistAndDateRangeAndTag(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end,
            @Param("tagName")   String tagName);

    // Products revenue grouped by tag (dashboard tag breakdown / performance report).
    // A product tagged with multiple tags contributes its full line total to each tag.
    @Query("SELECT new com.clinic.healinghouse.dto.TagRevenueDTO(t.name, COALESCE(SUM(pl.lineTotal), 0)) " +
           "FROM AppointmentProductLine pl JOIN pl.product.tags t " +
           "WHERE pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY t.name")
    List<TagRevenueDTO> sumProductRevenueByTagAndDateRange(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // Per-product units sold and revenue (performance report).
    @Query("SELECT new com.clinic.healinghouse.dto.ProductRevenueSummaryDTO(" +
           "    pl.product.id, pl.product.name, COALESCE(SUM(pl.quantity), 0), COALESCE(SUM(pl.lineTotal), 0)) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY pl.product.id, pl.product.name")
    List<ProductRevenueSummaryDTO> sumProductRevenueByProductAndDateRange(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // Products revenue grouped by tag x therapist (performance report cross-tab).
    @Query("SELECT new com.clinic.healinghouse.dto.TagTherapistRevenueDTO(" +
           "    t.name, pl.therapist.fullName, COALESCE(SUM(pl.lineTotal), 0)) " +
           "FROM AppointmentProductLine pl JOIN pl.product.tags t " +
           "WHERE pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY t.name, pl.therapist.fullName")
    List<TagTherapistRevenueDTO> sumProductRevenueByTagAndTherapist(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);
}