package com.clinic.healinghouse.config;

import com.clinic.healinghouse.repository.TherapistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time, idempotent fix-up for databases seeded before Therapist.owner existed (Bug_Report_v2
 * #4): the owner flag used to be inferred from commissionRate/fixedMonthlySalary both being
 * zero/null, which silently misclassified any new therapist saved before her payout terms were
 * configured. The new `owner` column defaults to false at the DB level, so every pre-existing row
 * — including the actual clinic owner's — comes back as non-owner after the schema update. This
 * runner finds the owner by {@code healinghouse.owner.full-name} (the same property DataSeeder's
 * seed row reads) and flags her, exactly once; every subsequent startup is a no-op. Deliberately its own
 * always-on component rather than folded into DataSeeder, which is dev/test-only (@Profile) — this
 * is a correctness fix for existing data, not master-data seeding, and prod/preprod have the same
 * pre-existing row needing the same one-time fix.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OwnerFlagBackfill implements CommandLineRunner {

    private final TherapistRepository therapistRepository;
    private final HealingHouseProperties properties;

    @Override
    @Transactional
    public void run(String... args) {
        String ownerFullName = properties.getOwner().getFullName();
        therapistRepository.findAll().stream()
                .filter(t -> ownerFullName.equals(t.getFullName()) && !t.isOwner())
                .forEach(t -> {
                    t.setOwner(true);
                    therapistRepository.save(t);
                    log.info("Backfilled owner=true for existing therapist id={} '{}'", t.getId(), t.getFullName());
                });
    }
}
