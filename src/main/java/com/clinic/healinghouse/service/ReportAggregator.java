package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.PeriodSummaryDTO;
import com.clinic.healinghouse.dto.TherapistEarningsDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.TherapistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Queries and groups appointment data by period/therapist for the Phase 3 dashboard and reports.
 * Only COMPLETED appointments count toward revenue/commission figures.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportAggregator {

    private final AppointmentRepository appointmentRepository;
    private final TherapistRepository therapistRepository;
    private final CommissionCalculator commissionCalculator;

    /** Completed appointments in the date range, ordered by date ascending. */
    public List<Appointment> getCompletedAppointments(LocalDate dateFrom, LocalDate dateTo) {
        return appointmentRepository.findByStatusAndDateRange(
                AppointmentStatus.COMPLETED, dateFrom.atStartOfDay(), dateTo.atTime(LocalTime.MAX));
    }

    /** Clinic-wide totals (appointments, unique patients, revenue split) for the date range. */
    public PeriodSummaryDTO getPeriodSummary(LocalDate dateFrom, LocalDate dateTo) {
        List<Appointment> appointments = getCompletedAppointments(dateFrom, dateTo);

        long uniquePatients = appointments.stream()
                .map(a -> a.getPatient().getId())
                .distinct()
                .count();

        BigDecimal totalServicesRevenue = appointments.stream()
                .map(Appointment::getTotalServiceAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalProductsRevenue = appointments.stream()
                .map(Appointment::getTotalProductAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PeriodSummaryDTO(dateFrom, dateTo, appointments.size(), uniquePatients,
                totalServicesRevenue, totalProductsRevenue);
    }

    /** Earnings for every active therapist in the period, ordered by full name. */
    public List<TherapistEarningsDTO> getTherapistEarnings(LocalDate dateFrom, LocalDate dateTo) {
        return getTherapistEarnings(therapistRepository.findByActiveTrueOrderByFullNameAsc(), dateFrom, dateTo);
    }

    /** Earnings for a specific subset of therapists — used by the therapist comparison report. */
    public List<TherapistEarningsDTO> getTherapistEarnings(List<Therapist> therapists, LocalDate dateFrom, LocalDate dateTo) {
        return therapists.stream()
                .map(t -> commissionCalculator.calculateEarnings(t, dateFrom, dateTo))
                .collect(Collectors.toList());
    }
}
