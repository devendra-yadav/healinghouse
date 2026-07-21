package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.dto.ProductRevenueSummaryDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.dto.TagTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TherapistRevenueDTO;
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

    // Blocks permanent deletion of a Product still referenced by appointment history.
    boolean existsByProduct_Id(Long productId);

    // Blocks permanent deletion of a PackageTemplate whose sold packages have been consumed in an appointment.
    boolean existsByPackageProductItem_PatientPackage_SourceTemplate_Id(Long packageTemplateId);

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

    // All products revenue for a therapist in a date range, regardless of tag — reporting only.
    @Query("SELECT COALESCE(SUM(pl.lineTotal), 0) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.therapist = :therapist " +
           "AND pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumAllProductRevenueByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);

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

    // Products revenue grouped by tag x therapist (performance report cross-tab). Grouped by
    // therapist.id so two therapists sharing a name don't have their revenue silently merged.
    @Query("SELECT new com.clinic.healinghouse.dto.TagTherapistRevenueDTO(" +
           "    t.name, pl.therapist.id, pl.therapist.fullName, COALESCE(SUM(pl.lineTotal), 0)) " +
           "FROM AppointmentProductLine pl JOIN pl.product.tags t " +
           "WHERE pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end " +
           "GROUP BY t.name, pl.therapist.id, pl.therapist.fullName")
    List<TagTherapistRevenueDTO> sumProductRevenueByTagAndTherapist(
            @Param("start") LocalDateTime start,
            @Param("end")   LocalDateTime end);

    // --- Actual Revenue report (post-discount, scoped to an already-filtered appointment id set) ---

    // Per-product units sold and post-discount (effective) revenue, scoped to a specific appointment id set —
    // every other report filter is already baked into that id set by the caller.
    @Query("SELECT new com.clinic.healinghouse.dto.ProductRevenueSummaryDTO(" +
           "    pl.product.id, pl.product.name, COALESCE(SUM(pl.quantity), 0), " +
           "    COALESCE(SUM(COALESCE(pl.discountedLineTotal, pl.lineTotal)), 0)) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.appointment.id IN :appointmentIds " +
           "GROUP BY pl.product.id, pl.product.name")
    List<ProductRevenueSummaryDTO> sumEffectiveProductRevenueByProductInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);

    // Raw (pre-discount) products revenue per line-level therapist, scoped to an appointment id set.
    // Grouped by therapist.id — see sumProductRevenueByTagAndTherapist's comment above.
    @Query("SELECT new com.clinic.healinghouse.dto.TherapistRevenueDTO(" +
           "    pl.therapist.id, pl.therapist.fullName, COALESCE(SUM(pl.lineTotal), 0)) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.appointment.id IN :appointmentIds " +
           "GROUP BY pl.therapist.id, pl.therapist.fullName")
    List<TherapistRevenueDTO> sumRawProductRevenueByTherapistInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);

    // Post-discount (effective) products revenue per line-level therapist, scoped to an appointment id set.
    @Query("SELECT new com.clinic.healinghouse.dto.TherapistRevenueDTO(" +
           "    pl.therapist.id, pl.therapist.fullName, COALESCE(SUM(COALESCE(pl.discountedLineTotal, pl.lineTotal)), 0)) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.appointment.id IN :appointmentIds " +
           "GROUP BY pl.therapist.id, pl.therapist.fullName")
    List<TherapistRevenueDTO> sumEffectiveProductRevenueByTherapistInAppointmentIds(
            @Param("appointmentIds") List<Long> appointmentIds);
}