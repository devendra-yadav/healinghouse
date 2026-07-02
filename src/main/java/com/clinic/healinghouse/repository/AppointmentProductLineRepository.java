package com.clinic.healinghouse.repository;

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

    // Total products revenue for a therapist in a date range (completed appts only)
    @Query("SELECT COALESCE(SUM(pl.lineTotal), 0) " +
           "FROM AppointmentProductLine pl " +
           "WHERE pl.appointment.therapist = :therapist " +
           "AND pl.appointment.status = 'COMPLETED' " +
           "AND pl.appointment.appointmentDateTime BETWEEN :start AND :end")
    BigDecimal sumProductRevenueByTherapistAndDateRange(
            @Param("therapist") Therapist therapist,
            @Param("start")     LocalDateTime start,
            @Param("end")       LocalDateTime end);
}