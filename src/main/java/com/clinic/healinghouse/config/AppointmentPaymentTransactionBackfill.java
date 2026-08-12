package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentPaymentTransaction;
import com.clinic.healinghouse.entity.AppointmentPaymentTransactionType;
import com.clinic.healinghouse.repository.AppointmentPaymentTransactionRepository;
import com.clinic.healinghouse.repository.AppointmentRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * One-time idempotent seed of {@link AppointmentPaymentTransaction} history for every appointment
 * that already existed before this ledger table was introduced (same self-healing-backfill style as
 * {@link StaleCheckConstraintBackfill}). Every appointment created/edited going forward already gets
 * its ledger rows written live by AppointmentService — this only covers the gap for pre-existing data,
 * so the new Cash Flow report isn't empty for historical dates on first boot after the upgrade.
 * <p>
 * Approximation, not a reconstruction: only {@code createdAt} is known for old rows, not the actual
 * date(s) money changed hands across possibly-multiple edits, so this seeds exactly one RECEIVED row
 * per appointment (dated at its createdAt) for the appointment's cash portion as it stands today —
 * amountPaid minus whatever wallet/package amount is currently applied.
 * <p>
 * Idempotency is checked per-appointment ({@link AppointmentPaymentTransactionRepository#existsByAppointment_Id}),
 * not via a single table-wide {@code count() > 0} guard. Spring Boot's embedded server can start
 * accepting requests before every {@link CommandLineRunner} has finished executing, so a real
 * cash-moving appointment edit could plausibly land — and write its own live ledger row — before this
 * runner reaches that appointment. A table-wide guard would see the table already non-empty on every
 * later boot and permanently skip backfilling every other appointment; the per-appointment check
 * instead self-heals on every restart, backfilling whatever's still missing while correctly leaving
 * alone any appointment that already has ledger coverage (live-written or previously backfilled),
 * without ever creating a duplicate row for it (Bug_Report_v7.md Finding 7).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentPaymentTransactionBackfill implements CommandLineRunner {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentPaymentTransactionRepository appointmentPaymentTransactionRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        int seeded = 0;
        for (Appointment appt : appointmentRepository.findAll()) {
            if (appointmentPaymentTransactionRepository.existsByAppointment_Id(appt.getId())) {
                continue;
            }
            BigDecimal cashPortion = nz(appt.getAmountPaid())
                    .subtract(nz(appt.getWalletAmountApplied()))
                    .subtract(nz(appt.getPackageAmountApplied()));
            if (cashPortion.signum() <= 0) {
                continue;
            }
            AppointmentPaymentTransaction txn = AppointmentPaymentTransaction.builder()
                    .appointment(appt)
                    .patient(appt.getPatient())
                    .type(AppointmentPaymentTransactionType.RECEIVED)
                    .amount(cashPortion)
                    .paymentMethod(appt.getPaymentMethod())
                    .note("Backfilled from pre-existing appointment data")
                    .build();
            appointmentPaymentTransactionRepository.save(txn);

            // createdAt is @CreationTimestamp-managed and `updatable = false` at the column level, so
            // Hibernate's own UPDATE would silently ignore a Java-side field mutation here — a direct
            // native UPDATE is the only way to back-date it to the appointment's own createdAt instead
            // of "now", which matters since the whole point of this backfill is realistic historical
            // dates for the Cash Flow report's date-range filter and trend chart.
            entityManager.createNativeQuery(
                            "update appointment_payment_transaction set created_at = :d where id = :id")
                    .setParameter("d", appt.getCreatedAt())
                    .setParameter("id", txn.getId())
                    .executeUpdate();
            seeded++;
        }
        if (seeded > 0) {
            log.info("Backfilled {} AppointmentPaymentTransaction row(s) from pre-existing appointments.", seeded);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
