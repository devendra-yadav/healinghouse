package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.Patient;
import com.clinic.healinghouse.entity.PatientWallet;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.entity.WalletTransactionType;
import com.clinic.healinghouse.repository.AppointmentRepository;
import com.clinic.healinghouse.repository.PatientRepository;
import com.clinic.healinghouse.repository.PatientWalletRepository;
import com.clinic.healinghouse.repository.WalletTransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class WalletService {

    private final PatientWalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;

    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long patientId) {
        return walletRepository.findById(patientId)
                .map(PatientWallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransaction> getTransactionHistory(Long patientId, Pageable pageable) {
        return transactionRepository.findByPatientIdOrderByCreatedAtDesc(patientId, pageable);
    }

    /** Looks up a patient's wallet, creating it with a zero balance if this is their first use of the feature. */
    private PatientWallet getOrCreateWallet(Long patientId) {
        return walletRepository.findById(patientId)
                .orElseGet(() -> {
                    Patient patient = patientRepository.findById(patientId)
                            .orElseThrow(() -> new EntityNotFoundException("Patient not found: " + patientId));
                    PatientWallet created = walletRepository.save(
                            PatientWallet.builder().patient(patient).balance(BigDecimal.ZERO).build());
                    log.info("Created wallet for patient id={}", patientId);
                    return created;
                });
    }

    public void topUp(Long patientId, BigDecimal amount, PaymentMethod method, String note) {
        validatePositive(amount, "Top-up amount");
        if (method == null) {
            throw new IllegalArgumentException("Payment method is required for a top-up.");
        }
        PatientWallet wallet = getOrCreateWallet(patientId);
        wallet.setBalance(wallet.getBalance().add(amount));
        wallet = persistBalance(wallet);

        recordTransaction(wallet.getPatient(), WalletTransactionType.TOP_UP, amount, method, null, note);
        log.info("Wallet top-up patient id={} amount={} method={} newBalance={}",
                patientId, amount, method, wallet.getBalance());
    }

    public void refund(Long patientId, BigDecimal amount, PaymentMethod method, String note) {
        validatePositive(amount, "Refund amount");
        if (method == null) {
            throw new IllegalArgumentException("Payment method is required for a refund.");
        }
        PatientWallet wallet = getOrCreateWallet(patientId);
        requireSufficientBalance(wallet, amount);
        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet = persistBalance(wallet);

        recordTransaction(wallet.getPatient(), WalletTransactionType.REFUND, amount, method, null, note);
        log.info("Wallet refund patient id={} amount={} method={} newBalance={}",
                patientId, amount, method, wallet.getBalance());
    }

    public void applyToAppointment(Long patientId, Long appointmentId, BigDecimal amount) {
        validatePositive(amount, "Wallet amount applied");
        PatientWallet wallet = getOrCreateWallet(patientId);
        requireSufficientBalance(wallet, amount);
        wallet.setBalance(wallet.getBalance().subtract(amount));
        wallet = persistBalance(wallet);

        recordTransaction(wallet.getPatient(), WalletTransactionType.USAGE, amount, null,
                appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Wallet usage patient id={} appointment id={} amount={} newBalance={}",
                patientId, appointmentId, amount, wallet.getBalance());
    }

    public void reverseForAppointment(Long patientId, Long appointmentId, BigDecimal amount) {
        validatePositive(amount, "Wallet amount reversed");
        PatientWallet wallet = getOrCreateWallet(patientId);
        wallet.setBalance(wallet.getBalance().add(amount));
        wallet = persistBalance(wallet);

        recordTransaction(wallet.getPatient(), WalletTransactionType.REVERSAL, amount, null,
                appointmentRepository.getReferenceById(appointmentId), null);
        log.info("Wallet reversal patient id={} appointment id={} amount={} newBalance={}",
                patientId, appointmentId, amount, wallet.getBalance());
    }

    private void recordTransaction(Patient patient, WalletTransactionType type, BigDecimal amount,
                                    PaymentMethod method, Appointment appointment, String note) {
        transactionRepository.save(WalletTransaction.builder()
                .patient(patient)
                .type(type)
                .amount(amount)
                .paymentMethod(method)
                .appointment(appointment)
                .note(note)
                .build());
    }

    /**
     * Forces the @Version-checked UPDATE to run synchronously (rather than at deferred
     * transaction commit), so a concurrent-edit conflict can actually be caught here
     * and turned into a friendly message instead of surfacing as an opaque failure later.
     */
    private PatientWallet persistBalance(PatientWallet wallet) {
        try {
            return walletRepository.saveAndFlush(wallet);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(
                    "This patient's wallet was just updated by someone else. Please refresh and try again.", ex);
        }
    }

    private void validatePositive(BigDecimal amount, String label) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(label + " must be greater than zero.");
        }
    }

    private void requireSufficientBalance(PatientWallet wallet, BigDecimal amount) {
        if (amount.compareTo(wallet.getBalance()) > 0) {
            throw new IllegalArgumentException(
                    "Insufficient wallet balance. Available: ₹" + wallet.getBalance() + ", requested: ₹" + amount);
        }
    }
}
