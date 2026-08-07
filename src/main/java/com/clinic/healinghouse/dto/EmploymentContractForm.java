package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.EmploymentContract;
import com.clinic.healinghouse.entity.Therapist;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Term-entry form (requirements/Employment_Contracts_Requirements_v1.md §4.1). */
@Data
public class EmploymentContractForm {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate joiningDate = LocalDate.now();
    private Integer contractPeriodMonths;
    private String position;
    private boolean probationApplicable;
    private Integer probationPeriodMonths;
    private BigDecimal probationSalary;
    private BigDecimal monthlySalary;
    private BigDecimal commissionPercent;
    private Integer performanceBonusThreshold;
    private BigDecimal performanceBonusAmount;
    private Integer noticePeriodMonths;

    /** Pre-populates a fresh (non-renewal) draft from the therapist's current profile fields
     *  (§4.1) — commissionPercent/monthlySalary/bonus fields mirror whatever payroll values are
     *  already set on the therapist, so the Owner starts from what's currently true. */
    public static EmploymentContractForm fromTherapist(Therapist therapist) {
        EmploymentContractForm form = new EmploymentContractForm();
        form.setMonthlySalary(therapist.getFixedMonthlySalary());
        if (therapist.getCommissionRate() != null) {
            form.setCommissionPercent(therapist.getCommissionRate().movePointRight(2));
        }
        form.setPerformanceBonusThreshold(therapist.getPerformanceBonusThreshold());
        form.setPerformanceBonusAmount(therapist.getPerformanceBonusAmount());
        return form;
    }

    /** Pre-populates a renewal draft from the previous contract's own terms (§5.6) — not the
     *  therapist's raw profile, since the previous contract is the more precise source of truth
     *  for what's currently being offered/renewed. */
    public static EmploymentContractForm fromPreviousContract(EmploymentContract previous) {
        EmploymentContractForm form = new EmploymentContractForm();
        form.setJoiningDate(previous.getJoiningDate());
        form.setContractPeriodMonths(previous.getContractPeriodMonths());
        form.setPosition(previous.getPosition());
        form.setProbationApplicable(false);
        form.setMonthlySalary(previous.getMonthlySalary());
        form.setCommissionPercent(previous.getCommissionPercent());
        form.setPerformanceBonusThreshold(previous.getPerformanceBonusThreshold());
        form.setPerformanceBonusAmount(previous.getPerformanceBonusAmount());
        form.setNoticePeriodMonths(previous.getNoticePeriodMonths());
        return form;
    }
}
