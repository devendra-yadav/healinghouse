package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentCombo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentComboRepository extends JpaRepository<AppointmentCombo, Long> {

    List<AppointmentCombo> findByAppointment(Appointment appointment);
}
