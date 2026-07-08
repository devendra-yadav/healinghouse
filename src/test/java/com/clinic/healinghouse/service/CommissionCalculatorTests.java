package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.TherapistEarningsDTO;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionCalculatorTests {

    @Mock
    private AppointmentServiceLineRepository serviceLineRepository;
    @Mock
    private AppointmentProductLineRepository productLineRepository;

    private CommissionCalculator calculator;

    private final LocalDate dateFrom = LocalDate.of(2026, 7, 1);
    private final LocalDate dateTo = LocalDate.of(2026, 7, 31);

    @BeforeEach
    void setUp() {
        calculator = new CommissionCalculator(serviceLineRepository, productLineRepository);
    }

    private Therapist therapist(String name, BigDecimal commissionRate, Integer bonusThreshold, BigDecimal bonusAmount) {
        return Therapist.builder()
                .id(1L)
                .fullName(name)
                .fixedMonthlySalary(BigDecimal.valueOf(20000))
                .commissionRate(commissionRate)
                .performanceBonusThreshold(bonusThreshold)
                .performanceBonusAmount(bonusAmount)
                .active(true)
                .build();
    }

    @Test
    void calculatesCommissionAsRevenueTimesRate() {
        Therapist t = therapist("Dr. A", BigDecimal.valueOf(0.10), 100, BigDecimal.valueOf(5000));
        when(serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.valueOf(10000));
        when(productLineRepository.sumProductRevenueByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.valueOf(2000));
        when(serviceLineRepository.countServicesPerformedByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.BONUS_TAG)))
                .thenReturn(50L);

        TherapistEarningsDTO earnings = calculator.calculateEarnings(t, dateFrom, dateTo);

        assertThat(earnings.serviceCommission()).isEqualByComparingTo("1000.00");
        assertThat(earnings.productCommission()).isEqualByComparingTo("200.00");
        assertThat(earnings.totalCommission()).isEqualByComparingTo("1200.00");
        assertThat(earnings.totalRevenue()).isEqualByComparingTo("12000");
    }

    @Test
    void bonusIsEarnedOnlyWhenServicesCountMeetsThreshold() {
        Therapist t = therapist("Dr. B", BigDecimal.valueOf(0.10), 100, BigDecimal.valueOf(5000));

        assertThat(calculator.calculateBonus(t, 99)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculateBonus(t, 100)).isEqualByComparingTo("5000");
        assertThat(calculator.calculateBonus(t, 150)).isEqualByComparingTo("5000");
    }

    @Test
    void bonusIsZeroWhenThresholdOrAmountNotConfigured() {
        Therapist noThreshold = therapist("Dr. C", BigDecimal.valueOf(0.10), null, BigDecimal.valueOf(5000));
        Therapist noAmount = therapist("Dr. D", BigDecimal.valueOf(0.10), 100, null);

        assertThat(calculator.calculateBonus(noThreshold, 200)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculateBonus(noAmount, 200)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void ownerHasNoCommissionOrBonusRegardlessOfRevenue() {
        Therapist owner = Therapist.builder()
                .id(99L)
                .fullName("Marcia Gomes Yadav")
                .fixedMonthlySalary(BigDecimal.ZERO)
                .commissionRate(BigDecimal.ZERO)
                .active(true)
                .build();

        TherapistEarningsDTO earnings = calculator.calculateEarnings(owner, dateFrom, dateTo);

        assertThat(earnings.servicesRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(earnings.productsRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(earnings.totalCommission()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(earnings.bonusEarned()).isFalse();
        assertThat(earnings.totalVariablePay()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void zeroRevenueProducesZeroCommissionAndNoBonus() {
        Therapist t = therapist("Dr. E", BigDecimal.valueOf(0.15), 100, BigDecimal.valueOf(5000));
        when(serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.ZERO);
        when(productLineRepository.sumProductRevenueByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.ZERO);
        when(serviceLineRepository.countServicesPerformedByTherapistAndDateRangeAndTag(eq(t), any(), any(), eq(CommissionCalculator.BONUS_TAG)))
                .thenReturn(0L);

        TherapistEarningsDTO earnings = calculator.calculateEarnings(t, dateFrom, dateTo);

        assertThat(earnings.totalCommission()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(earnings.bonusEarned()).isFalse();
        assertThat(earnings.totalVariablePay()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void perLineAttributionKeepsEachTherapistsRevenueIndependent() {
        Therapist a = therapist("Dr. A", BigDecimal.valueOf(0.10), 100, BigDecimal.valueOf(5000));
        Therapist b = therapist("Dr. B", BigDecimal.valueOf(0.20), 100, BigDecimal.valueOf(5000));

        when(serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(eq(a), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.valueOf(1000));
        when(serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(eq(b), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.valueOf(5000));
        when(productLineRepository.sumProductRevenueByTherapistAndDateRangeAndTag(any(), any(), any(), eq(CommissionCalculator.COMMISSION_TAG)))
                .thenReturn(BigDecimal.ZERO);
        when(serviceLineRepository.countServicesPerformedByTherapistAndDateRangeAndTag(any(), any(), any(), eq(CommissionCalculator.BONUS_TAG)))
                .thenReturn(1L);

        TherapistEarningsDTO earningsA = calculator.calculateEarnings(a, dateFrom, dateTo);
        TherapistEarningsDTO earningsB = calculator.calculateEarnings(b, dateFrom, dateTo);

        assertThat(earningsA.servicesRevenue()).isEqualByComparingTo("1000");
        assertThat(earningsA.serviceCommission()).isEqualByComparingTo("100.00");
        assertThat(earningsB.servicesRevenue()).isEqualByComparingTo("5000");
        assertThat(earningsB.serviceCommission()).isEqualByComparingTo("1000.00");
    }
}
