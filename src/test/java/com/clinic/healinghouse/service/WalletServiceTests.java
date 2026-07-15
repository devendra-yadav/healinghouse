package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.PatientWallet;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.PatientRepository;
import com.clinic.healinghouse.repository.PatientWalletRepository;
import com.clinic.healinghouse.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTests {

    @Mock
    private PatientWalletRepository walletRepository;
    @Mock
    private WalletTransactionRepository transactionRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    private WalletService walletService;

    private static final Long PATIENT_ID = 1L;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, transactionRepository, patientRepository, appointmentRepository, new HealingHouseProperties());
    }

    private Patient patient() {
        return Patient.builder().id(PATIENT_ID).fullName("Jane Doe").build();
    }

    private PatientWallet wallet(BigDecimal balance) {
        return PatientWallet.builder().patient(patient()).balance(balance).build();
    }

    @Test
    void topUp_createsWalletAndCreditsBalance_whenWalletDoesNotExist() {
        when(walletRepository.findById(PATIENT_ID)).thenReturn(Optional.empty());
        when(patientRepository.findById(PATIENT_ID)).thenReturn(Optional.of(patient()));
        when(walletRepository.saveAndFlush(any(PatientWallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.topUp(PATIENT_ID, BigDecimal.valueOf(500), PaymentMethod.CASH, "initial top-up");

        // Two saveAndFlush calls: getOrCreateWallet flushes the brand-new zero-balance row first (so a
        // concurrent-creation race would surface here, not at a later flush point — Bug_Report_v2 #7),
        // then persistBalance flushes the actual credited balance. getValue() returns the last (final) one.
        ArgumentCaptor<PatientWallet> savedWallet = ArgumentCaptor.forClass(PatientWallet.class);
        verify(walletRepository, times(2)).saveAndFlush(savedWallet.capture());
        assertThat(savedWallet.getValue().getBalance()).isEqualByComparingTo("500");

        ArgumentCaptor<WalletTransaction> txn = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("TOP_UP");
        assertThat(txn.getValue().getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(txn.getValue().getAmount()).isEqualByComparingTo("500");
    }

    @Test
    void applyToAppointment_throws_whenAmountExceedsBalance() {
        when(walletRepository.findById(PATIENT_ID)).thenReturn(Optional.of(wallet(BigDecimal.valueOf(100))));

        assertThatThrownBy(() -> walletService.applyToAppointment(PATIENT_ID, 10L, BigDecimal.valueOf(200)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient wallet balance");

        verify(walletRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void refund_throws_whenAmountExceedsBalance() {
        when(walletRepository.findById(PATIENT_ID)).thenReturn(Optional.of(wallet(BigDecimal.valueOf(50))));

        assertThatThrownBy(() -> walletService.refund(PATIENT_ID, BigDecimal.valueOf(75), PaymentMethod.UPI, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient wallet balance");

        verify(walletRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void reverseForAppointment_creditsBalance_regardlessOfAmount() {
        when(walletRepository.findById(PATIENT_ID)).thenReturn(Optional.of(wallet(BigDecimal.ZERO)));
        when(walletRepository.saveAndFlush(any(PatientWallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(appointmentRepository.getReferenceById(anyLong())).thenReturn(Appointment.builder().id(10L).build());

        walletService.reverseForAppointment(PATIENT_ID, 10L, BigDecimal.valueOf(9999));

        ArgumentCaptor<PatientWallet> savedWallet = ArgumentCaptor.forClass(PatientWallet.class);
        verify(walletRepository).saveAndFlush(savedWallet.capture());
        assertThat(savedWallet.getValue().getBalance()).isEqualByComparingTo("9999");

        ArgumentCaptor<WalletTransaction> txn = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(txn.capture());
        assertThat(txn.getValue().getType().name()).isEqualTo("REVERSAL");
        assertThat(txn.getValue().getAppointment().getId()).isEqualTo(10L);
    }

    @Test
    void getBalance_returnsZero_whenNoWalletRow() {
        when(walletRepository.findById(PATIENT_ID)).thenReturn(Optional.empty());

        assertThat(walletService.getBalance(PATIENT_ID)).isEqualByComparingTo(BigDecimal.ZERO);

        verify(walletRepository, never()).save(any());
        verify(walletRepository, never()).saveAndFlush(any());
    }
}
