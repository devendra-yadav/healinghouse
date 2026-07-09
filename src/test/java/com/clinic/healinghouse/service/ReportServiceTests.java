package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.ComparisonReportDTO;
import com.clinic.healinghouse.dto.DailyReportDTO;
import com.clinic.healinghouse.dto.PatientFirstVisitDTO;
import com.clinic.healinghouse.dto.PatientReportDTO;
import com.clinic.healinghouse.dto.PeriodReportDTO;
import com.clinic.healinghouse.dto.PeriodSummaryDTO;
import com.clinic.healinghouse.dto.PerformanceReportDTO;
import com.clinic.healinghouse.dto.ProductPerformanceDTO;
import com.clinic.healinghouse.dto.ProductRevenueSummaryDTO;
import com.clinic.healinghouse.dto.ServicePerformanceDTO;
import com.clinic.healinghouse.dto.ServiceRevenueSummaryDTO;
import com.clinic.healinghouse.dto.ServiceTherapistRevenueDTO;
import com.clinic.healinghouse.dto.TagRevenueDTO;
import com.clinic.healinghouse.dto.TherapistEarningsDTO;
import com.clinic.healinghouse.dto.TherapistPatientMetricsDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.repository.TherapistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTests {

    @Mock private ReportAggregator reportAggregator;
    @Mock private DashboardService dashboardService;
    @Mock private TherapistRepository therapistRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentServiceLineRepository serviceLineRepository;
    @Mock private AppointmentProductLineRepository productLineRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;

    private ReportService reportService;

    private final LocalDate dateFrom = LocalDate.of(2026, 7, 1);
    private final LocalDate dateTo = LocalDate.of(2026, 7, 31);

    private long nextAppointmentId = 1;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(reportAggregator, dashboardService, therapistRepository,
                appointmentRepository, serviceLineRepository, productLineRepository,
                clinicServiceRepository, productRepository);
    }

    private Therapist therapist(long id, String name) {
        return Therapist.builder().id(id).fullName(name).active(true).build();
    }

    private Patient patient(long id, String name) {
        return Patient.builder().id(id).fullName(name).active(true).build();
    }

    private Appointment appointment(Patient patient, Therapist therapist, LocalDateTime dateTime) {
        return Appointment.builder()
                .id(nextAppointmentId++)
                .patient(patient)
                .therapist(therapist)
                .appointmentDateTime(dateTime)
                .status(AppointmentStatus.COMPLETED)
                .build();
    }

    private TherapistEarningsDTO zeroEarnings(Therapist therapist) {
        return new TherapistEarningsDTO(therapist, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void dailyReportDelegatesToReportAggregatorForTheGivenDate() {
        LocalDate date = LocalDate.of(2026, 7, 8);
        PeriodSummaryDTO summary = new PeriodSummaryDTO(date, date, 5, 4, BigDecimal.valueOf(1000), BigDecimal.valueOf(200));
        TherapistEarningsDTO earnings = zeroEarnings(therapist(1, "Dr. A"));

        when(reportAggregator.getPeriodSummary(date, date)).thenReturn(summary);
        when(reportAggregator.getTherapistEarnings(date, date)).thenReturn(List.of(earnings));

        DailyReportDTO report = reportService.getDailyReport(date);

        assertThat(report.date()).isEqualTo(date);
        assertThat(report.summary()).isEqualTo(summary);
        assertThat(report.therapistEarnings()).containsExactly(earnings);
    }

    @Test
    void comparisonReportResolvesOnlyTheRequestedTherapistSubset() {
        Therapist a = therapist(1, "Dr. A");
        Therapist b = therapist(2, "Dr. B");
        List<Long> ids = List.of(1L, 2L);
        TherapistEarningsDTO earningsA = zeroEarnings(a);
        TherapistEarningsDTO earningsB = zeroEarnings(b);

        when(therapistRepository.findAllById(ids)).thenReturn(List.of(a, b));
        when(reportAggregator.getTherapistEarnings(List.of(a, b), dateFrom, dateTo))
                .thenReturn(List.of(earningsA, earningsB));

        ComparisonReportDTO report = reportService.getTherapistComparison(ids, dateFrom, dateTo);

        assertThat(report.therapistEarnings()).containsExactly(earningsA, earningsB);
    }

    @Test
    void periodReportIncludesTagRevenueAndProductPerformance() {
        PeriodSummaryDTO summary = new PeriodSummaryDTO(dateFrom, dateTo, 10, 8, BigDecimal.valueOf(5000), BigDecimal.valueOf(1000));
        when(reportAggregator.getPeriodSummary(dateFrom, dateTo)).thenReturn(summary);
        when(reportAggregator.getTherapistEarnings(dateFrom, dateTo)).thenReturn(List.of());

        List<TagRevenueDTO> tagRevenue = List.of(new TagRevenueDTO("Massage", BigDecimal.valueOf(3000)));
        when(dashboardService.getTagRevenueBreakdown(dateFrom, dateTo)).thenReturn(tagRevenue);

        Product product = Product.builder().id(1L).name("Oil").price(BigDecimal.TEN)
                .stockQuantity(2).reorderLevel(5).active(true).build();
        when(productLineRepository.sumProductRevenueByProductAndDateRange(any(), any()))
                .thenReturn(List.of(new ProductRevenueSummaryDTO(1L, "Oil", 3, BigDecimal.valueOf(300))));
        when(productRepository.findAllById(List.of(1L))).thenReturn(List.of(product));

        PeriodReportDTO report = reportService.getPeriodReport(dateFrom, dateTo);

        assertThat(report.tagRevenue()).isEqualTo(tagRevenue);
        assertThat(report.productPerformance()).hasSize(1);

        ProductPerformanceDTO p = report.productPerformance().get(0);
        assertThat(p.productName()).isEqualTo("Oil");
        assertThat(p.unitsSold()).isEqualTo(3);
        assertThat(p.revenue()).isEqualByComparingTo("300");
        assertThat(p.lowStock()).isTrue();
    }

    @Test
    void patientAcquisitionClassifiesNewAndRepeatPatientsCorrectlyOverallAndPerTherapist() {
        LocalDate rangeFrom = LocalDate.of(2026, 7, 1);
        LocalDate rangeTo = LocalDate.of(2026, 7, 10);
        LocalDateTime start = rangeFrom.atStartOfDay();
        LocalDateTime end = rangeTo.atTime(LocalTime.MAX);

        Therapist therapistA = therapist(1, "Dr. A");
        Therapist therapistB = therapist(2, "Dr. B");
        Patient newPatient = patient(10, "New Patient");
        Patient repeatPatient = patient(20, "Repeat Patient");

        LocalDateTime newVisit = LocalDateTime.of(2026, 7, 5, 10, 0);
        LocalDateTime repeatVisitInRange = LocalDateTime.of(2026, 7, 3, 9, 0);
        LocalDateTime repeatPatientFirstEverVisit = LocalDateTime.of(2026, 6, 1, 9, 0);

        Appointment newPatientAppt = appointment(newPatient, therapistA, newVisit);
        Appointment repeatPatientAppt = appointment(repeatPatient, therapistB, repeatVisitInRange);

        when(appointmentRepository.findByDateRange(start, end))
                .thenReturn(List.of(newPatientAppt, repeatPatientAppt));
        when(appointmentRepository.findFirstVisitDatesForPatientsActiveInRange(start, end))
                .thenReturn(List.of(
                        new PatientFirstVisitDTO(newPatient.getId(), newVisit),
                        new PatientFirstVisitDTO(repeatPatient.getId(), repeatPatientFirstEverVisit)
                ));
        when(therapistRepository.findByActiveTrueOrderByFullNameAsc())
                .thenReturn(List.of(therapistA, therapistB));

        PatientReportDTO report = reportService.getPatientAcquisitionReport(rangeFrom, rangeTo);

        assertThat(report.totalNewPatients()).isEqualTo(1);
        assertThat(report.totalRepeatPatients()).isEqualTo(1);
        assertThat(report.overallRetentionRate()).isEqualTo(50.0);

        TherapistPatientMetricsDTO metricsA = report.therapistMetrics().stream()
                .filter(m -> m.therapist().equals(therapistA)).findFirst().orElseThrow();
        TherapistPatientMetricsDTO metricsB = report.therapistMetrics().stream()
                .filter(m -> m.therapist().equals(therapistB)).findFirst().orElseThrow();

        assertThat(metricsA.newPatients()).isEqualTo(1);
        assertThat(metricsA.repeatPatients()).isEqualTo(0);
        assertThat(metricsB.newPatients()).isEqualTo(0);
        assertThat(metricsB.repeatPatients()).isEqualTo(1);

        // Trend attributes the "new" day to the patient's actual first-visit date, not every day they appear.
        List<String> labels = report.trend().labels();
        assertThat(labels).hasSize(10);
        int newDayIndex = labels.indexOf("05 Jul");
        int repeatDayIndex = labels.indexOf("03 Jul");

        assertThat(report.trend().newPatients().get(newDayIndex)).isEqualTo(1L);
        assertThat(report.trend().repeatPatients().get(newDayIndex)).isEqualTo(0L);
        assertThat(report.trend().newPatients().get(repeatDayIndex)).isEqualTo(0L);
        assertThat(report.trend().repeatPatients().get(repeatDayIndex)).isEqualTo(1L);
    }

    @Test
    void performanceReportPicksTopRevenueTherapistPerServiceAndFlagsLowStock() {
        when(serviceLineRepository.sumServiceRevenueByServiceAndDateRange(any(), any()))
                .thenReturn(List.of(new ServiceRevenueSummaryDTO(1L, "Massage", 5, BigDecimal.valueOf(2500))));
        when(clinicServiceRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(ClinicService.builder().id(1L).name("Massage").active(true)
                        .price(BigDecimal.valueOf(500)).build()));
        when(serviceLineRepository.sumServiceRevenueByServiceAndTherapist(any(), any()))
                .thenReturn(List.of(
                        new ServiceTherapistRevenueDTO("Massage", "Dr. A", BigDecimal.valueOf(1000)),
                        new ServiceTherapistRevenueDTO("Massage", "Dr. B", BigDecimal.valueOf(1500))
                ));
        when(serviceLineRepository.sumServiceRevenueByTagAndTherapist(any(), any())).thenReturn(List.of());

        when(productLineRepository.sumProductRevenueByProductAndDateRange(any(), any()))
                .thenReturn(List.of(new ProductRevenueSummaryDTO(2L, "Oil", 4, BigDecimal.valueOf(400))));
        Product lowStockProduct = Product.builder().id(2L).name("Oil").price(BigDecimal.TEN)
                .stockQuantity(1).reorderLevel(5).active(true).build();
        when(productRepository.findAllById(List.of(2L))).thenReturn(List.of(lowStockProduct));
        when(productLineRepository.sumProductRevenueByTagAndTherapist(any(), any())).thenReturn(List.of());

        when(dashboardService.getTagRevenueBreakdown(dateFrom, dateTo)).thenReturn(List.of());

        PerformanceReportDTO report = reportService.getProductPerformanceReport(dateFrom, dateTo);

        assertThat(report.services()).hasSize(1);
        ServicePerformanceDTO service = report.services().get(0);
        assertThat(service.topTherapistName()).isEqualTo("Dr. B");
        assertThat(service.averagePrice()).isEqualByComparingTo("500.00");

        assertThat(report.products()).hasSize(1);
        ProductPerformanceDTO product = report.products().get(0);
        assertThat(product.lowStock()).isTrue();
    }
}
