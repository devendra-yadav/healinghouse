package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.ContractCancelForm;
import com.clinic.healinghouse.dto.ContractContentUpdateDTO;
import com.clinic.healinghouse.dto.EmploymentContractForm;
import com.clinic.healinghouse.entity.ContractStatus;
import com.clinic.healinghouse.entity.EmploymentContract;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.EmploymentContractRepository;
import com.clinic.healinghouse.util.ContractPdfService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** requirements/Employment_Contracts_Requirements_v1.md §5.2-§5.8. */
@ExtendWith(MockitoExtension.class)
class ContractServiceTests {

    @Mock private EmploymentContractRepository contractRepository;
    @Mock private TherapistService therapistService;
    @Mock private ContractTemplateRenderer templateRenderer;
    @Mock private ContractPdfService pdfService;

    private ContractService service;

    @BeforeEach
    void setUp() {
        service = new ContractService(contractRepository, therapistService, templateRenderer, pdfService, new HealingHouseProperties());
    }

    private Therapist therapist(long id) {
        return Therapist.builder().id(id).fullName("Priya S.").build();
    }

    private User user(long id) {
        return User.builder().id(id).username("owner").build();
    }

    private EmploymentContract.EmploymentContractBuilder contractBuilder(long id, ContractStatus status) {
        return EmploymentContract.builder()
                .id(id)
                .therapist(therapist(1L))
                .contractNumber("HHC-2026-0001")
                .status(status)
                .joiningDate(LocalDate.now())
                .position("Therapist")
                .monthlySalary(BigDecimal.valueOf(20000))
                .noticePeriodMonths(1)
                .contractBodyHtml("<p>body</p>");
    }

    private EmploymentContractForm validForm() {
        EmploymentContractForm form = new EmploymentContractForm();
        form.setJoiningDate(LocalDate.now());
        form.setPosition("Therapist");
        form.setMonthlySalary(BigDecimal.valueOf(20000));
        form.setNoticePeriodMonths(1);
        return form;
    }

    // ── Status-transition guards ──

    @Test
    void approveThrowsWhenNotDraft() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.approve(1L, user(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft contract can be approved");
        verify(therapistService, never()).save(any());
    }

    @Test
    void cancelThrowsWhenNotApproved() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.DRAFT).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.cancel(1L, new ContractCancelForm(), user(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved contract can be cancelled");
    }

    @Test
    void deleteThrowsWhenNotDraft() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft contract can be deleted");
        verify(contractRepository, never()).delete(any());
    }

    @Test
    void updateContentThrowsWhenNotDraft() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        ContractContentUpdateDTO dto = new ContractContentUpdateDTO();
        dto.setContractBodyHtml("<p>edited</p>");

        assertThatThrownBy(() -> service.updateContent(1L, dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("draft contract's content can be edited");
    }

    // ── approve(): payroll sync ──

    @Test
    void approveSyncsAllPayrollFieldsWhenPopulated() {
        Therapist t = therapist(1L);
        EmploymentContract contract = contractBuilder(1L, ContractStatus.DRAFT)
                .therapist(t)
                .commissionPercent(BigDecimal.valueOf(10))
                .performanceBonusThreshold(100)
                .performanceBonusAmount(BigDecimal.valueOf(5000))
                .build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(pdfService.render(any())).thenReturn(new byte[]{1, 2, 3});
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(1L, user(1L));

        assertThat(t.getCommissionRate()).isEqualByComparingTo("0.10");
        assertThat(t.getFixedMonthlySalary()).isEqualByComparingTo("20000");
        assertThat(t.getPerformanceBonusThreshold()).isEqualTo(100);
        assertThat(t.getPerformanceBonusAmount()).isEqualByComparingTo("5000");
        verify(therapistService).save(t);
    }

    @Test
    void approveLeavesBlankCommissionAndBonusUntouchedOnTherapist() {
        Therapist t = therapist(1L);
        t.setCommissionRate(BigDecimal.valueOf(0.25));
        t.setPerformanceBonusThreshold(50);
        t.setPerformanceBonusAmount(BigDecimal.valueOf(1000));
        EmploymentContract contract = contractBuilder(1L, ContractStatus.DRAFT).therapist(t).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(pdfService.render(any())).thenReturn(new byte[]{1, 2, 3});
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(1L, user(1L));

        // Contract carried no commission/bonus clause — Therapist's existing values must survive untouched.
        assertThat(t.getCommissionRate()).isEqualByComparingTo("0.25");
        assertThat(t.getPerformanceBonusThreshold()).isEqualTo(50);
        assertThat(t.getPerformanceBonusAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void approveRejectsCommissionOver100PercentBeforeTouchingTherapist() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.DRAFT)
                .commissionPercent(BigDecimal.valueOf(150))
                .build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.approve(1L, user(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 100%");
        verify(therapistService, never()).save(any());
        verify(pdfService, never()).render(any());
    }

    @Test
    void approveSupersedesPreviousContract() {
        EmploymentContract previous = contractBuilder(2L, ContractStatus.APPROVED).build();
        EmploymentContract contract = contractBuilder(1L, ContractStatus.DRAFT).previousContract(previous).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(pdfService.render(any())).thenReturn(new byte[]{1});
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(1L, user(1L));

        assertThat(previous.getStatus()).isEqualTo(ContractStatus.SUPERSEDED);
    }

    // ── cancel(): never touches Therapist payroll fields ──

    @Test
    void cancelNeverTouchesTherapist() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(1L, new ContractCancelForm(), user(1L));

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        verify(therapistService, never()).save(any());
    }

    // ── generateDraft() ──

    @Test
    void generateDraftReopensExistingDraftInsteadOfDuplicating() {
        Therapist t = therapist(1L);
        when(therapistService.getById(1L)).thenReturn(t);
        when(contractRepository.findByTherapist_IdAndStatus(1L, ContractStatus.APPROVED)).thenReturn(List.of());
        EmploymentContract existingDraft = contractBuilder(5L, ContractStatus.DRAFT).build();
        when(contractRepository.findByTherapist_IdAndStatus(1L, ContractStatus.DRAFT)).thenReturn(List.of(existingDraft));

        EmploymentContract result = service.generateDraft(1L, validForm(), user(1L));

        assertThat(result).isSameAs(existingDraft);
        verify(contractRepository, never()).save(any());
    }

    @Test
    void generateDraftRejectsFreshDraftWhenApprovedContractExists() {
        Therapist t = therapist(1L);
        when(therapistService.getById(1L)).thenReturn(t);
        when(contractRepository.findByTherapist_IdAndStatus(1L, ContractStatus.APPROVED))
                .thenReturn(List.of(contractBuilder(3L, ContractStatus.APPROVED).build()));

        assertThatThrownBy(() -> service.generateDraft(1L, validForm(), user(1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active contract");
    }

    // ── acknowledge() ──

    @Test
    void acknowledgeIsIdempotent() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        contract.setAcknowledgedAt(LocalDate.now().atStartOfDay());
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));

        service.acknowledge(1L, user(1L));

        verify(contractRepository, never()).save(any());
    }

    @Test
    void acknowledgeSetsTimestampOnFirstCall() {
        EmploymentContract contract = contractBuilder(1L, ContractStatus.APPROVED).build();
        when(contractRepository.findById(1L)).thenReturn(Optional.of(contract));
        when(contractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.acknowledge(1L, user(1L));

        assertThat(contract.getAcknowledgedAt()).isNotNull();
    }

    // ── getById() ──

    @Test
    void getByIdThrowsWhenMissing() {
        when(contractRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L)).isInstanceOf(EntityNotFoundException.class);
    }
}
