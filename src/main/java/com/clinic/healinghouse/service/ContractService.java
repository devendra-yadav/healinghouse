package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ContractCancelForm;
import com.clinic.healinghouse.dto.ContractContentUpdateDTO;
import com.clinic.healinghouse.dto.ContractListRowDTO;
import com.clinic.healinghouse.dto.EmploymentContractForm;
import com.clinic.healinghouse.entity.ContractStatus;
import com.clinic.healinghouse.entity.EmploymentContract;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.EmploymentContractRepository;
import com.clinic.healinghouse.util.ContractPdfService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

/**
 * Lifecycle for {@link EmploymentContract} (requirements/Employment_Contracts_Requirements_v1.md §5).
 * Status-transition guards throw {@link IllegalStateException}; bad input throws
 * {@link IllegalArgumentException}; missing rows throw {@link EntityNotFoundException} — mirrors
 * {@code ExpenseService}'s exception conventions.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ContractService {

    private static final int MAX_CONTRACT_NUMBER_ATTEMPTS = 3;

    private final EmploymentContractRepository contractRepository;
    private final TherapistService therapistService;
    private final ContractTemplateRenderer templateRenderer;
    private final ContractPdfService pdfService;
    private final HealingHouseProperties properties;

    @Transactional(readOnly = true)
    public EmploymentContract getById(Long id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Contract not found: " + id));
    }

    @Transactional(readOnly = true)
    public boolean belongsToTherapist(Long contractId, Long therapistId) {
        return contractRepository.findById(contractId)
                .map(c -> c.getTherapist().getId().equals(therapistId))
                .orElse(false);
    }

    /** The one contract relevant to the Therapist detail page's summary card — an in-progress
     *  DRAFT if one exists, else the current APPROVED contract, else empty. */
    @Transactional(readOnly = true)
    public Optional<EmploymentContract> findCurrentOrDraft(Long therapistId) {
        List<EmploymentContract> drafts = contractRepository.findByTherapist_IdAndStatus(therapistId, ContractStatus.DRAFT);
        if (!drafts.isEmpty()) return Optional.of(drafts.get(0));
        List<EmploymentContract> approved = contractRepository.findByTherapist_IdAndStatus(therapistId, ContractStatus.APPROVED);
        if (!approved.isEmpty()) return Optional.of(approved.get(0));
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<ContractListRowDTO> findHistoryForTherapist(Long therapistId) {
        return contractRepository.findByTherapist_IdOrderByCreatedAtDesc(therapistId).stream()
                .map(ContractListRowDTO::from)
                .toList();
    }

    /** The therapist's current APPROVED contract — used to pre-populate the Renew form (§5.6). */
    @Transactional(readOnly = true)
    public EmploymentContract getApprovedForTherapist(Long therapistId) {
        List<EmploymentContract> approved = contractRepository.findByTherapist_IdAndStatus(therapistId, ContractStatus.APPROVED);
        if (approved.isEmpty()) {
            throw new IllegalStateException("This therapist has no active contract to renew.");
        }
        return approved.get(0);
    }

    /** §5.2, §5.6: rejects a fresh draft if an APPROVED contract already exists (use Renew instead);
     *  reopens an existing in-progress DRAFT rather than creating a duplicate. */
    public EmploymentContract generateDraft(Long therapistId, EmploymentContractForm form, User currentUser) {
        Therapist therapist = therapistService.getById(therapistId);

        if (!contractRepository.findByTherapist_IdAndStatus(therapistId, ContractStatus.APPROVED).isEmpty()) {
            throw new IllegalStateException("This therapist already has an active contract — use Renew instead.");
        }
        List<EmploymentContract> existingDrafts = contractRepository.findByTherapist_IdAndStatus(therapistId, ContractStatus.DRAFT);
        if (!existingDrafts.isEmpty()) {
            return existingDrafts.get(0);
        }

        validateForm(form);
        EmploymentContract draft = EmploymentContract.builder()
                .therapist(therapist)
                .status(ContractStatus.DRAFT)
                .createdBy(currentUser)
                .build();
        applyForm(draft, form);
        return persistNewDraft(draft, therapist, form);
    }

    /** §5.6: only from an APPROVED contract; links previousContract, superseded only once the new
     *  draft is itself APPROVED (§approve below) — an abandoned renewal draft never affects the
     *  still-APPROVED original. */
    public EmploymentContract renewDraft(Long currentContractId, EmploymentContractForm form, User currentUser) {
        EmploymentContract current = getById(currentContractId);
        if (current.getStatus() != ContractStatus.APPROVED) {
            throw new IllegalStateException("Only an approved contract can be renewed.");
        }
        validateForm(form);
        Therapist therapist = current.getTherapist();
        EmploymentContract draft = EmploymentContract.builder()
                .therapist(therapist)
                .status(ContractStatus.DRAFT)
                .createdBy(currentUser)
                .previousContract(current)
                .build();
        applyForm(draft, form);
        return persistNewDraft(draft, therapist, form);
    }

    /** §5.2: DRAFT-only, callable any number of times. */
    public EmploymentContract updateContent(Long id, ContractContentUpdateDTO dto) {
        EmploymentContract contract = getById(id);
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new IllegalStateException("Only a draft contract's content can be edited.");
        }
        contract.setContractBodyHtml(dto.getContractBodyHtml());
        EmploymentContract saved = contractRepository.save(contract);
        log.info("Updated draft content for contract id={}", id);
        return saved;
    }

    /** §5.4: locks content, renders the final PDF, syncs payroll fields onto Therapist, and
     *  supersedes previousContract if this is a renewal — all in one transaction, so a failure at
     *  any step rolls back the whole thing (no partial state). */
    public EmploymentContract approve(Long id, User currentUser) {
        EmploymentContract contract = getById(id);
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new IllegalStateException("Only a draft contract can be approved.");
        }
        if (contract.getCommissionPercent() != null && contract.getCommissionPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Commission percent cannot exceed 100%.");
        }

        byte[] pdf = pdfService.render(contract);
        contract.setPdfContent(pdf);
        contract.setStatus(ContractStatus.APPROVED);
        contract.setApprovedAt(LocalDateTime.now());
        contract.setApprovedBy(currentUser);

        syncPayrollFields(contract);

        if (contract.getPreviousContract() != null) {
            EmploymentContract previous = contract.getPreviousContract();
            previous.setStatus(ContractStatus.SUPERSEDED);
            contractRepository.save(previous);
        }

        EmploymentContract saved = contractRepository.save(contract);
        log.info("Approved contract id={} number={} therapist={}", saved.getId(), saved.getContractNumber(), saved.getTherapist().getId());
        return saved;
    }

    /** §5.5: APPROVED-only. Deliberately does NOT touch Therapist payroll fields — the confirmed
     *  default is that they stay at their last-synced values until manually changed or a new
     *  contract is approved. Do not "fix" this into an auto-reset. */
    public EmploymentContract cancel(Long id, ContractCancelForm form, User currentUser) {
        EmploymentContract contract = getById(id);
        if (contract.getStatus() != ContractStatus.APPROVED) {
            throw new IllegalStateException("Only an approved contract can be cancelled.");
        }
        contract.setStatus(ContractStatus.CANCELLED);
        contract.setCancelledAt(LocalDateTime.now());
        contract.setCancelledBy(currentUser);
        contract.setCancellationReason(form.getCancellationReason());
        EmploymentContract saved = contractRepository.save(contract);
        log.info("Cancelled contract id={}", id);
        return saved;
    }

    /** §5.8: DRAFT-only hard delete — a draft has no legal weight and nothing else references it. */
    public void delete(Long id) {
        EmploymentContract contract = getById(id);
        if (contract.getStatus() != ContractStatus.DRAFT) {
            throw new IllegalStateException("Only a draft contract can be deleted.");
        }
        contractRepository.delete(contract);
        log.info("Deleted draft contract id={}", id);
    }

    /** §5.7: idempotent — a double-click is harmless, no error. */
    public void acknowledge(Long id, User currentUser) {
        EmploymentContract contract = getById(id);
        if (contract.getAcknowledgedAt() != null) {
            return;
        }
        if (contract.getStatus() != ContractStatus.APPROVED) {
            throw new IllegalStateException("Only an approved contract can be acknowledged.");
        }
        contract.setAcknowledgedAt(LocalDateTime.now());
        contractRepository.save(contract);
        log.info("Contract id={} acknowledged by user id={}", id, currentUser.getId());
    }

    @Transactional(readOnly = true)
    public byte[] getPdf(Long id) {
        EmploymentContract contract = getById(id);
        if (contract.getStatus() != ContractStatus.APPROVED) {
            throw new IllegalStateException("This contract has not been approved yet — no PDF available.");
        }
        return contract.getPdfContent();
    }

    private void syncPayrollFields(EmploymentContract contract) {
        Therapist therapist = contract.getTherapist();
        if (contract.getCommissionPercent() != null) {
            therapist.setCommissionRate(contract.getCommissionPercent().movePointLeft(2));
        }
        therapist.setFixedMonthlySalary(contract.getMonthlySalary());
        if (contract.getPerformanceBonusThreshold() != null && contract.getPerformanceBonusAmount() != null) {
            therapist.setPerformanceBonusThreshold(contract.getPerformanceBonusThreshold());
            therapist.setPerformanceBonusAmount(contract.getPerformanceBonusAmount());
        }
        therapistService.save(therapist);
    }

    /** Assigns a contract number and renders the initial body, retrying on a rare concurrent
     *  numbering collision (§5.6, §10). */
    private EmploymentContract persistNewDraft(EmploymentContract draft, Therapist therapist, EmploymentContractForm form) {
        LocalDate generatedDate = LocalDate.now();
        IllegalStateException lastError = null;
        for (int attempt = 1; attempt <= MAX_CONTRACT_NUMBER_ATTEMPTS; attempt++) {
            String contractNumber = nextContractNumber();
            draft.setContractNumber(contractNumber);
            draft.setContractBodyHtml(templateRenderer.render(therapist, form, contractNumber, generatedDate));
            try {
                EmploymentContract saved = contractRepository.save(draft);
                log.info("Generated draft contract id={} number={} therapist={}", saved.getId(), saved.getContractNumber(), therapist.getId());
                return saved;
            } catch (DataIntegrityViolationException e) {
                lastError = new IllegalStateException(
                        "Could not assign a unique contract number after " + MAX_CONTRACT_NUMBER_ATTEMPTS + " attempts.", e);
            }
        }
        throw lastError;
    }

    private String nextContractNumber() {
        int year = Year.now().getValue();
        LocalDateTime yearStart = LocalDateTime.of(year, 1, 1, 0, 0);
        LocalDateTime yearEnd = LocalDateTime.of(year + 1, 1, 1, 0, 0);
        long sequence = contractRepository.countCreatedBetween(yearStart, yearEnd) + 1;
        String prefix = properties.getContracts().getContractNumberPrefix();
        return String.format("%s-%d-%04d", prefix, year, sequence);
    }

    private void applyForm(EmploymentContract contract, EmploymentContractForm form) {
        contract.setJoiningDate(form.getJoiningDate());
        contract.setContractPeriodMonths(form.getContractPeriodMonths());
        contract.setPosition(form.getPosition());
        contract.setProbationApplicable(form.isProbationApplicable());
        contract.setProbationPeriodMonths(form.isProbationApplicable() ? form.getProbationPeriodMonths() : null);
        contract.setProbationSalary(form.isProbationApplicable() ? form.getProbationSalary() : null);
        contract.setMonthlySalary(form.getMonthlySalary());
        contract.setCommissionPercent(form.getCommissionPercent());
        contract.setPerformanceBonusThreshold(form.getPerformanceBonusThreshold());
        contract.setPerformanceBonusAmount(form.getPerformanceBonusAmount());
        contract.setNoticePeriodMonths(form.getNoticePeriodMonths());
    }

    private void validateForm(EmploymentContractForm form) {
        if (form.getJoiningDate() == null) {
            throw new IllegalArgumentException("Joining date is required.");
        }
        if (form.getPosition() == null || form.getPosition().isBlank()) {
            throw new IllegalArgumentException("Position is required.");
        }
        if (form.getMonthlySalary() == null || form.getMonthlySalary().signum() <= 0) {
            throw new IllegalArgumentException("Monthly salary must be greater than zero.");
        }
        if (form.getNoticePeriodMonths() == null || form.getNoticePeriodMonths() <= 0) {
            throw new IllegalArgumentException("Notice period is required.");
        }
        if (form.isProbationApplicable()) {
            if (form.getProbationPeriodMonths() == null || form.getProbationPeriodMonths() < 1 || form.getProbationPeriodMonths() > 6) {
                throw new IllegalArgumentException("Probation period must be between 1 and 6 months.");
            }
            if (form.getProbationSalary() == null || form.getProbationSalary().signum() <= 0) {
                throw new IllegalArgumentException("Probation salary is required when probation applies.");
            }
        }
        boolean hasThreshold = form.getPerformanceBonusThreshold() != null;
        boolean hasAmount = form.getPerformanceBonusAmount() != null;
        if (hasThreshold != hasAmount) {
            throw new IllegalArgumentException("Performance bonus threshold and amount must be provided together.");
        }
        if (form.getCommissionPercent() != null && form.getCommissionPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Commission percent cannot exceed 100%.");
        }
    }
}
