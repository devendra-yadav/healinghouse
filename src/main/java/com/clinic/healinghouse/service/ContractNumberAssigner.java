package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.EmploymentContract;
import com.clinic.healinghouse.repository.EmploymentContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a single contract-number-assignment attempt in its own transaction, isolated from
 * {@link ContractService#persistNewDraft}'s outer transaction/session. A unique-constraint
 * violation on {@code contractNumber} marks the underlying Hibernate persistence context
 * rollback-only even once the caller catches the translated exception — retrying inside the same
 * transaction would then fail every subsequent attempt for that unrelated reason rather than the
 * actual numbering collision. {@code REQUIRES_NEW} gives each attempt its own independently
 * committable/rollbackable transaction, so only the failed attempt is discarded.
 */
@Service
@RequiredArgsConstructor
class ContractNumberAssigner {

    private final EmploymentContractRepository contractRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmploymentContract saveInNewTransaction(EmploymentContract draft) {
        return contractRepository.save(draft);
    }
}
