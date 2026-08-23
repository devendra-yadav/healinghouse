package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.CashFlowReportDTO;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentPaymentTransaction;
import com.clinic.healinghouse.entity.AppointmentPaymentTransactionType;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.PackageTransaction;
import com.clinic.healinghouse.entity.PackageTransactionType;
import com.clinic.healinghouse.entity.PatientPackage;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.entity.WalletTransactionType;
import com.clinic.healinghouse.repository.AppointmentPaymentTransactionRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.PackageTransactionRepository;
import com.clinic.healinghouse.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * See CashFlowReportAggregator's javadoc for the four-ledger, no-double-counting design this locks
 * down: inflow only from AppointmentPaymentTransaction / PackageTransaction.PURCHASE /
 * WalletTransaction.TOP_UP, outflow only from Expense(ACTIVE) / *.REFUND / a negative
 * AppointmentPaymentTransaction correction — USAGE/REVERSAL rows are never queried at all.
 */
@ExtendWith(MockitoExtension.class)
class CashFlowReportAggregatorTests {

    @Mock private AppointmentPaymentTransactionRepository appointmentPaymentTransactionRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private PackageTransactionRepository packageTransactionRepository;
    @Mock private ExpenseRepository expenseRepository;

    private CashFlowReportAggregator aggregator;

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 31);

    @BeforeEach
    void setUp() {
        aggregator = new CashFlowReportAggregator(appointmentPaymentTransactionRepository,
                walletTransactionRepository, packageTransactionRepository, expenseRepository,
                new HealingHouseProperties());
    }

    private Patient patient(String name) {
        return Patient.builder().id(1L).fullName(name).build();
    }

    @Test
    void inflowSourcesSumIndependentlyAndOutflowNeverIncludesThem() {
        AppointmentPaymentTransaction cashPayment = AppointmentPaymentTransaction.builder()
                .type(AppointmentPaymentTransactionType.RECEIVED)
                .amount(BigDecimal.valueOf(1000))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("Jane Doe"))
                .appointment(appointmentAt(2026, 7, 10, 9, 0))
                .build();
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any()))
                .thenReturn(List.of(cashPayment));

        WalletTransaction topUp = WalletTransaction.builder()
                .type(WalletTransactionType.TOP_UP)
                .amount(BigDecimal.valueOf(2000))
                .paymentMethod(PaymentMethod.UPI)
                .patient(patient("Jane Doe"))
                .createdAt(LocalDateTime.of(2026, 7, 11, 9, 0))
                .build();
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(topUp));

        PackageTransaction purchase = PackageTransaction.builder()
                .type(PackageTransactionType.PURCHASE)
                .amount(BigDecimal.valueOf(3000))
                .paymentMethod(PaymentMethod.CARD)
                .patientPackage(PatientPackage.builder().patient(patient("Jane Doe")).build())
                .createdAt(LocalDateTime.of(2026, 7, 12, 9, 0))
                .build();
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(purchase));

        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO))
                .thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 20));

        assertThat(report.summary().appointmentCashInflow()).isEqualByComparingTo("1000");
        assertThat(report.summary().walletTopUps()).isEqualByComparingTo("2000");
        assertThat(report.summary().packageSales()).isEqualByComparingTo("3000");
        assertThat(report.summary().totalInflow()).isEqualByComparingTo("6000");
        assertThat(report.summary().totalOutflow()).isEqualByComparingTo("0");
        assertThat(report.summary().netCashFlow()).isEqualByComparingTo("6000");
        assertThat(report.ledger().getTotalElements()).isEqualTo(3);
    }

    @Test
    void negativeCorrectionFlaggedCashReturnedCountsAsOutflowNotAsReducedInflow() {
        AppointmentPaymentTransaction correction = AppointmentPaymentTransaction.builder()
                .type(AppointmentPaymentTransactionType.CORRECTED)
                .amount(BigDecimal.valueOf(-400))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("John Roe"))
                .appointment(appointmentAt(2026, 7, 15, 9, 0))
                .cashPhysicallyReturned(true)
                .build();
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any()))
                .thenReturn(List.of(correction));
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO)).thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 20));

        assertThat(report.summary().appointmentCashInflow()).isEqualByComparingTo("0");
        assertThat(report.summary().appointmentPaymentCorrections()).isEqualByComparingTo("400");
        assertThat(report.summary().totalInflow()).isEqualByComparingTo("0");
        assertThat(report.summary().totalOutflow()).isEqualByComparingTo("400");
        assertThat(report.summary().netCashFlow()).isEqualByComparingTo("-400");

        assertThat(report.ledger().getContent()).hasSize(1);
        assertThat(report.ledger().getContent().get(0).direction()).isEqualTo("OUT");
        assertThat(report.ledger().getContent().get(0).amount()).isEqualByComparingTo("400");
    }

    /** Bug_Report_v7.md Finding 8: a downward correction NOT flagged cashPhysicallyReturned is a
     *  data-entry fix, not a real cash-flow event — must be excluded from totals and the ledger
     *  entirely, not counted as outflow the way an unconditional negative-amount check previously did. */
    @Test
    void negativeCorrectionNotFlaggedCashReturnedIsExcludedFromOutflowAndLedger() {
        AppointmentPaymentTransaction typoFix = AppointmentPaymentTransaction.builder()
                .type(AppointmentPaymentTransactionType.CORRECTED)
                .amount(BigDecimal.valueOf(-4500))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("John Roe"))
                .appointment(appointmentAt(2026, 7, 15, 9, 0))
                .build();
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any()))
                .thenReturn(List.of(typoFix));
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO)).thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 20));

        assertThat(report.summary().appointmentCashInflow()).isEqualByComparingTo("0");
        assertThat(report.summary().appointmentPaymentCorrections()).isEqualByComparingTo("0");
        assertThat(report.summary().totalInflow()).isEqualByComparingTo("0");
        assertThat(report.summary().totalOutflow()).isEqualByComparingTo("0");
        assertThat(report.summary().netCashFlow()).isEqualByComparingTo("0");
        assertThat(report.ledger().getContent()).isEmpty();
    }

    /** Bug_Report_v7.md Finding 16: the ledger label must reflect the transaction's actual type,
     *  not just its amount's sign — an upward CORRECTED row (e.g. fixing a previously under-
     *  recorded payment) is still a correction, not a fresh "Appointment Payment", even though
     *  both are inflow. */
    @Test
    void upwardCorrectionIsLabeledAsCorrectionNotAsAPlainPayment() {
        AppointmentPaymentTransaction upwardCorrection = AppointmentPaymentTransaction.builder()
                .type(AppointmentPaymentTransactionType.CORRECTED)
                .amount(BigDecimal.valueOf(300))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("Jane Doe"))
                .appointment(appointmentAt(2026, 7, 15, 9, 0))
                .build();
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any()))
                .thenReturn(List.of(upwardCorrection));
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO)).thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 20));

        assertThat(report.summary().appointmentCashInflow()).isEqualByComparingTo("300");
        assertThat(report.ledger().getContent()).hasSize(1);
        assertThat(report.ledger().getContent().get(0).source()).isEqualTo("Appointment Correction");
        assertThat(report.ledger().getContent().get(0).direction()).isEqualTo("IN");
    }

    @Test
    void refundsAndExpensesAreOutflowAndReduceNetCashFlow() {
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any())).thenReturn(List.of());

        WalletTransaction refund = WalletTransaction.builder()
                .type(WalletTransactionType.REFUND)
                .amount(BigDecimal.valueOf(500))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("Jane Doe"))
                .createdAt(LocalDateTime.of(2026, 7, 20, 9, 0))
                .build();
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(refund));

        PackageTransaction packageRefund = PackageTransaction.builder()
                .type(PackageTransactionType.REFUND)
                .amount(BigDecimal.valueOf(600))
                .paymentMethod(PaymentMethod.CASH)
                .patientPackage(PatientPackage.builder().patient(patient("Jane Doe")).build())
                .createdAt(LocalDateTime.of(2026, 7, 21, 9, 0))
                .build();
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(packageRefund));

        Expense expense = Expense.builder()
                .amount(BigDecimal.valueOf(200))
                .category(ExpenseCategory.builder().name("Rent").build())
                .expenseDate(LocalDate.of(2026, 7, 22))
                .build();
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO))
                .thenReturn(List.of(expense));

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 20));

        assertThat(report.summary().walletRefunds()).isEqualByComparingTo("500");
        assertThat(report.summary().packageRefunds()).isEqualByComparingTo("600");
        assertThat(report.summary().expenses()).isEqualByComparingTo("200");
        assertThat(report.summary().totalOutflow()).isEqualByComparingTo("1300");
        assertThat(report.summary().netCashFlow()).isEqualByComparingTo("-1300");
    }

    @Test
    void ledgerIsPaginatedInMemory() {
        List<AppointmentPaymentTransaction> txns = List.of(
                paymentAt(2026, 7, 1), paymentAt(2026, 7, 2), paymentAt(2026, 7, 3));
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any())).thenReturn(txns);
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO)).thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, PageRequest.of(0, 2));

        assertThat(report.ledger().getTotalElements()).isEqualTo(3);
        assertThat(report.ledger().getContent()).hasSize(2);
        // Sorted newest-first: 7/3 then 7/2 on page 0.
        assertThat(report.ledger().getContent().get(0).date()).isEqualTo(LocalDateTime.of(2026, 7, 3, 9, 0));
    }

    @Test
    void unpagedRequestReturnsEveryLedgerEntry() {
        List<AppointmentPaymentTransaction> txns = List.of(
                paymentAt(2026, 7, 1), paymentAt(2026, 7, 2), paymentAt(2026, 7, 3));
        when(appointmentPaymentTransactionRepository.findByAppointment_AppointmentDateTimeBetween(any(), any())).thenReturn(txns);
        when(walletTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(packageTransactionRepository.findByTypeInAndCreatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, FROM, TO)).thenReturn(List.of());

        CashFlowReportDTO report = aggregator.getCashFlowReport(FROM, TO, Pageable.unpaged());

        assertThat(report.ledger().getContent()).hasSize(3);
    }

    private AppointmentPaymentTransaction paymentAt(int y, int m, int d) {
        return AppointmentPaymentTransaction.builder()
                .type(AppointmentPaymentTransactionType.RECEIVED)
                .amount(BigDecimal.valueOf(100))
                .paymentMethod(PaymentMethod.CASH)
                .patient(patient("Jane Doe"))
                .appointment(appointmentAt(y, m, d, 9, 0))
                .build();
    }

    private Appointment appointmentAt(int y, int m, int d, int h, int min) {
        return Appointment.builder().appointmentDateTime(LocalDateTime.of(y, m, d, h, min)).build();
    }
}
