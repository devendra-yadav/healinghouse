package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.dto.ComboDiscountSummaryDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentCombo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AppointmentComboRepository extends JpaRepository<AppointmentCombo, Long> {

    List<AppointmentCombo> findByAppointment(Appointment appointment);

    // Blocks permanent deletion of a Combo still referenced by appointment history.
    boolean existsByCombo_Id(Long comboId);

    // Total combo discount per appointment, scoped to an appointment id set — avoids lazily
    // loading each Appointment's combos collection just to sum getTotalComboDiscount().
    @Query("SELECT new com.clinic.healinghouse.dto.ComboDiscountSummaryDTO(" +
           "    ac.appointment.id, COALESCE(SUM(ac.discountAmount), 0)) " +
           "FROM AppointmentCombo ac " +
           "WHERE ac.appointment.id IN :appointmentIds " +
           "GROUP BY ac.appointment.id")
    List<ComboDiscountSummaryDTO> sumDiscountByAppointmentIds(@Param("appointmentIds") List<Long> appointmentIds);
}
