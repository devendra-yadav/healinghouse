package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.EmploymentContractForm;
import com.clinic.healinghouse.entity.Therapist;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds the initial, owner-editable {@code contractBodyHtml} for a new draft
 * (requirements/Employment_Contracts_Requirements_v1.md §5.2, §9) from the therapist's profile and
 * the submitted {@link EmploymentContractForm} — omitting every clause whose backing field is blank
 * (§5.3). Deliberately plain Java string building, not a Thymeleaf text-template — consistent with
 * how this app already builds other generated text content (CSV rows, PDF tables) in Java rather
 * than through the template engine. Excludes the clinic letterhead and signature/stamp block, which
 * ContractPdfService wraps around this body only at approve-time (§5.4, §7) — the body here is just
 * the editable contract text itself.
 */
@Component
public class ContractTemplateRenderer {

    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,##0.00");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    public String render(Therapist therapist, EmploymentContractForm form, String contractNumber, LocalDate generatedDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>EMPLOYMENT CONTRACT</h2>");
        sb.append("<p><strong>Contract No:</strong> ").append(escape(contractNumber))
                .append(" &nbsp;&nbsp; <strong>Date:</strong> ").append(DATE_FORMAT.format(generatedDate)).append("</p>");
        sb.append("<p>This Employment Contract (\"Agreement\") is entered into between Healing House Clinic &amp; Academy ")
                .append("(\"the Clinic\") and ").append(escape(therapist.getFullName())).append(" (\"the Employee\").</p>");

        sb.append(buildEmployeeDetailsClause(therapist));
        sb.append(buildPositionClause(form));
        if (form.isProbationApplicable()) {
            sb.append(buildProbationClause(form));
        }
        sb.append(buildCompensationClause(form));
        sb.append(buildWorkingConditionsClause());
        sb.append(buildConfidentialityClause());
        sb.append(buildTerminationClause(form));

        return sb.toString();
    }

    private String buildEmployeeDetailsClause(Therapist therapist) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h4>1. EMPLOYEE DETAILS</h4><ul>");
        sb.append(item("Name", escape(therapist.getFullName())));
        if (isSet(therapist.getPhone())) {
            sb.append(item("Phone", escape(therapist.getPhone())));
        }
        if (isSet(therapist.getEmail())) {
            sb.append(item("Email", escape(therapist.getEmail())));
        }
        if (isSet(therapist.getAddress())) {
            sb.append(item("Address", escape(therapist.getAddress())));
        }
        if (isSet(therapist.getAadhaarNumber())) {
            sb.append(item("Aadhaar No.", escape(therapist.getAadhaarNumber())));
        }
        if (isSet(therapist.getPanNumber())) {
            sb.append(item("PAN", escape(therapist.getPanNumber())));
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String buildPositionClause(EmploymentContractForm form) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h4>2. POSITION AND JOINING</h4><ul>");
        sb.append(item("Position", escape(form.getPosition())));
        sb.append(item("Effective From", DATE_FORMAT.format(form.getJoiningDate())));
        if (form.getContractPeriodMonths() != null) {
            sb.append(item("Contract Period", form.getContractPeriodMonths() + " month(s) from the date of joining"));
        } else {
            sb.append(item("Contract Period", "Ongoing — no fixed end date"));
        }
        sb.append("</ul>");
        if (form.getContractPeriodMonths() != null) {
            sb.append("<p>This Agreement is valid for the period above, unless terminated earlier as per the Termination clause below.</p>");
        } else {
            sb.append("<p>This is an ongoing engagement, continuing until terminated by either party as per the Termination clause below.</p>");
        }
        return sb.toString();
    }

    private String buildProbationClause(EmploymentContractForm form) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h4>3. PROBATION</h4><ul>");
        sb.append(item("Probation Period", form.getProbationPeriodMonths() + " month(s) from the date of joining"));
        sb.append(item("Probation Salary", "&#8377;" + AMOUNT_FORMAT.format(form.getProbationSalary()) + " /month"));
        sb.append("</ul>");
        return sb.toString();
    }

    private String buildCompensationClause(EmploymentContractForm form) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h4>4. COMPENSATION</h4><ul>");
        String salaryLabel = form.isProbationApplicable() ? "Monthly Salary (after probation)" : "Monthly Salary";
        sb.append(item(salaryLabel, "&#8377;" + AMOUNT_FORMAT.format(form.getMonthlySalary())));

        if (form.getCommissionPercent() != null) {
            sb.append(item("Commission", AMOUNT_FORMAT.format(form.getCommissionPercent())
                    + "% on applicable service/product revenue, as per the Clinic's prevailing commission policy"));
        }

        if (form.getPerformanceBonusThreshold() != null && form.getPerformanceBonusAmount() != null) {
            sb.append(item("Performance Bonus", "&#8377;" + AMOUNT_FORMAT.format(form.getPerformanceBonusAmount())
                    + " for completing " + form.getPerformanceBonusThreshold() + "+ sessions within a calendar month"));
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String buildWorkingConditionsClause() {
        return "<h4>5. WORKING CONDITIONS</h4><p>The Employee shall observe the Clinic's working hours, "
                + "conduct, and operational policies as communicated from time to time.</p>";
    }

    private String buildConfidentialityClause() {
        return "<h4>6. CONFIDENTIALITY</h4><p>The Employee shall maintain strict confidentiality of all "
                + "patient information and Clinic business information, both during and after the term of this Agreement.</p>";
    }

    private String buildTerminationClause(EmploymentContractForm form) {
        return "<h4>7. TERMINATION</h4><ul>"
                + item("Notice Period", form.getNoticePeriodMonths() + " month(s) written notice, required from either party")
                + "</ul>"
                + "<p>Either party may terminate this Agreement by serving the notice period stated above in writing. "
                + "The Clinic reserves the right to terminate this Agreement immediately, without notice or pay in lieu "
                + "thereof, in cases of proven misconduct, breach of confidentiality, or gross negligence. Upon termination, "
                + "the Employee shall settle all outstanding dues, hand over any Clinic property in their possession, and "
                + "shall continue to be bound by the Confidentiality clause above.</p>";
    }

    private String item(String label, String value) {
        return "<li><strong>" + label + ":</strong> " + value + "</li>";
    }

    private boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
