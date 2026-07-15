package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.RevenueReportDTO;
import com.clinic.healinghouse.dto.RevenueReportFilter;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.repository.AppointmentComboRepository;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Regression test for Bug_Report_v3 #5: the summary cards must stay strictly COMPLETED-only —
 * Net Revenue = Gross Revenue - Combo Discounts - Manual Discounts must hold exactly, with advance
 * payments on non-COMPLETED appointments shown only as their own standalone figure, never folded in.
 * RevenueReportAggregator previously had zero dedicated tests (it's mocked out entirely in
 * ReportServiceTests, which covers the other five reports, not this one).
 */
@ExtendWith(MockitoExtension.class)
class RevenueReportAggregatorTests {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentComboRepository appointmentComboRepository;
    @Mock private AppointmentServiceLineRepository serviceLineRepository;
    @Mock private AppointmentProductLineRepository productLineRepository;
    @Mock private ClinicServiceRepository clinicServiceRepository;
    @Mock private ProductRepository productRepository;

    private RevenueReportAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new RevenueReportAggregator(appointmentRepository, appointmentComboRepository,
                serviceLineRepository, productLineRepository, clinicServiceRepository, productRepository,
                new HealingHouseProperties());
    }

    @Test
    void summary_mixedCompletedAndScheduledAppointments_netRevenueIsCompletedOnly() {
        Appointment completed = Appointment.builder()
                .id(1L)
                .appointmentDateTime(LocalDateTime.of(2026, 7, 10, 10, 0))
                .status(AppointmentStatus.COMPLETED)
                .totalServiceAmount(BigDecimal.valueOf(5000))
                .totalProductAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .grandTotal(BigDecimal.valueOf(5000))
                .amountPaid(BigDecimal.valueOf(5000))
                .walletAmountApplied(BigDecimal.ZERO)
                .build();

        Appointment scheduledWithAdvance = Appointment.builder()
                .id(2L)
                .appointmentDateTime(LocalDateTime.of(2026, 7, 11, 10, 0))
                .status(AppointmentStatus.SCHEDULED)
                .totalServiceAmount(BigDecimal.valueOf(2000))
                .totalProductAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .grandTotal(BigDecimal.valueOf(2000))
                .amountPaid(BigDecimal.valueOf(800))
                .walletAmountApplied(BigDecimal.ZERO)
                .build();

        // getRevenueReport calls findAll(Specification) twice with different specs, in this order:
        // completedSpec first, then advanceSpec.
        when(appointmentRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(completed))
                .thenReturn(List.of(scheduledWithAdvance));
        when(appointmentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(appointmentComboRepository.sumDiscountByAppointmentIds(anyList())).thenReturn(List.of());
        when(serviceLineRepository.sumRawServiceRevenueByTherapistInAppointmentIds(anyList())).thenReturn(List.of());
        when(serviceLineRepository.sumEffectiveServiceRevenueByTherapistInAppointmentIds(anyList())).thenReturn(List.of());
        when(productLineRepository.sumRawProductRevenueByTherapistInAppointmentIds(anyList())).thenReturn(List.of());
        when(productLineRepository.sumEffectiveProductRevenueByTherapistInAppointmentIds(anyList())).thenReturn(List.of());
        when(serviceLineRepository.sumEffectiveServiceRevenueByServiceInAppointmentIds(anyList())).thenReturn(List.of());
        when(productLineRepository.sumEffectiveProductRevenueByProductInAppointmentIds(anyList())).thenReturn(List.of());

        RevenueReportFilter filter = new RevenueReportFilter(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                null, null, null, null, null, null, null, false);
        RevenueReportDTO report = aggregator.getRevenueReport(filter, PageRequest.of(0, 20));

        // Net Revenue = Gross Revenue - Combo Discounts - Manual Discounts, exactly — the identity the
        // requirements doc promises. Prior to the fix this was inflated by the Scheduled appointment's
        // ₹800 advance payment (netRevenue would have been 5800, breaking the identity).
        assertThat(report.summary().netRevenue()).isEqualByComparingTo("5000");
        assertThat(report.summary().grossRevenue()).isEqualByComparingTo("5000");
        assertThat(report.summary().netRevenue())
                .isEqualByComparingTo(report.summary().grossRevenue()
                        .subtract(report.summary().comboDiscount())
                        .subtract(report.summary().manualDiscount()));

        assertThat(report.summary().collected()).isEqualByComparingTo("5000");
        assertThat(report.summary().appointmentCount()).isEqualTo(1);

        // The advance is still surfaced, just as its own standalone figure, never folded in above.
        assertThat(report.summary().advanceReceived()).isEqualByComparingTo("800");
    }
}
