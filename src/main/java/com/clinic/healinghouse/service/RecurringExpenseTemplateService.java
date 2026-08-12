package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.RecurringExpenseTemplateForm;
import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseCategory;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.RecurringExpenseTemplate;
import com.clinic.healinghouse.entity.User;
import com.clinic.healinghouse.repository.ExpenseCategoryRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.RecurringExpenseTemplateRepository;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Manages recurring expense templates and the actual generation logic (requirements/Expenses_
 * Requirements_v1.md §3.3, §5.3) — shared by both the daily {@link RecurringExpenseScheduler} and
 * the manual "Generate Now" button, so both trigger paths behave identically.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class RecurringExpenseTemplateService {

    private final RecurringExpenseTemplateRepository recurringExpenseTemplateRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseRepository expenseRepository;
    private final PermissionService permissionService;

    @Transactional(readOnly = true)
    public List<RecurringExpenseTemplate> findAllActive() {
        return recurringExpenseTemplateRepository.findByActiveTrueOrderByLabelAsc();
    }

    /** Excludes templates under a restrictedVisibility category when the caller is THERAPIST_PLUS
     *  (requirements/Expenses_Requirements_v1.md §5.5, §254 — recurring templates fall under the
     *  same EXPENSES module/visibility rule as one-off expense rows) — backs the recurring
     *  templates list page. Filtered at the DB level (not in-memory) so the returned Page's
     *  totals/element count stay accurate. */
    @Transactional(readOnly = true)
    public Page<RecurringExpenseTemplate> findAll(Pageable pageable) {
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS) {
            return recurringExpenseTemplateRepository.findByCategory_RestrictedVisibilityFalse(pageable);
        }
        return recurringExpenseTemplateRepository.findAll(pageable);
    }

    /** Throws EntityNotFoundException (not AccessDeniedException) for a THERAPIST_PLUS caller
     *  requesting a restricted-category template — avoids leaking that row's existence, mirroring
     *  ExpenseService.getById. Also guards edit/pause/resume/generateNow, which all route through
     *  this method. */
    @Transactional(readOnly = true)
    public RecurringExpenseTemplate getById(Long id) {
        RecurringExpenseTemplate template = recurringExpenseTemplateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Recurring expense template not found: " + id));
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS && template.getCategory().isRestrictedVisibility()) {
            throw new EntityNotFoundException("Recurring expense template not found: " + id);
        }
        return template;
    }

    public RecurringExpenseTemplate create(RecurringExpenseTemplateForm form, User createdBy) {
        RecurringExpenseTemplate template = RecurringExpenseTemplate.builder().createdBy(createdBy).build();
        applyForm(template, form);
        RecurringExpenseTemplate saved = recurringExpenseTemplateRepository.save(template);
        log.info("Created recurring expense template id={} label='{}'", saved.getId(), saved.getLabel());
        return saved;
    }

    public RecurringExpenseTemplate update(Long id, RecurringExpenseTemplateForm form) {
        RecurringExpenseTemplate template = getById(id);
        applyForm(template, form);
        RecurringExpenseTemplate saved = recurringExpenseTemplateRepository.save(template);
        log.info("Updated recurring expense template id={}", saved.getId());
        return saved;
    }

    public void pause(Long id) {
        RecurringExpenseTemplate template = getById(id);
        template.setActive(false);
        recurringExpenseTemplateRepository.save(template);
        log.info("Paused recurring expense template id={}", id);
    }

    public void resume(Long id) {
        RecurringExpenseTemplate template = getById(id);
        template.setActive(true);
        recurringExpenseTemplateRepository.save(template);
        log.info("Resumed recurring expense template id={}", id);
    }

    private void applyForm(RecurringExpenseTemplate template, RecurringExpenseTemplateForm form) {
        if (!StringUtils.hasText(form.getLabel())) {
            throw new IllegalArgumentException("Label is required.");
        }
        if (form.getCategoryId() == null) {
            throw new IllegalArgumentException("Category is required.");
        }
        if (form.getDefaultAmount() == null || form.getDefaultAmount().signum() <= 0) {
            throw new IllegalArgumentException("Default amount must be greater than zero.");
        }
        if (form.getFrequency() == null) {
            throw new IllegalArgumentException("Frequency is required.");
        }
        // startDate is only accepted on create (see below, it's fixed for life once created and
        // ignored on update) — the edit form renders it disabled, so browsers never submit it and
        // form.getStartDate() is legitimately null on an update. Validate against whichever value
        // is actually in effect for this save.
        LocalDate effectiveStartDate = template.getId() == null ? form.getStartDate() : template.getStartDate();
        if (template.getId() == null && form.getStartDate() == null) {
            throw new IllegalArgumentException("Start date is required.");
        }
        if (form.getEndDate() != null && form.getEndDate().isBefore(effectiveStartDate)) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }
        ExpenseCategory category = expenseCategoryRepository.findById(form.getCategoryId())
                .orElseThrow(() -> new EntityNotFoundException("Expense category not found: " + form.getCategoryId()));
        if (permissionService.currentRole() == AppRole.THERAPIST_PLUS && category.isRestrictedVisibility()) {
            // Mirrors getById's existence-masking — a THERAPIST_PLUS caller who crafts a request
            // with a restricted category id directly (bypassing the filtered dropdown) shouldn't
            // learn the category exists.
            throw new EntityNotFoundException("Expense category not found: " + form.getCategoryId());
        }

        template.setCategory(category);
        template.setLabel(form.getLabel().trim());
        template.setDefaultAmount(form.getDefaultAmount());
        template.setVendorName(form.getVendorName());
        template.setPaymentMethod(form.getPaymentMethod());
        template.setFrequency(form.getFrequency());
        template.setEndDate(form.getEndDate());
        template.setActive(form.isActive());
        // startDate is only ever set on create — the edit form renders it disabled ("Start date
        // can't be changed once created — pause/resume instead"), so it's never part of an update
        // submission; ignoring it outright on update (rather than trusting a submitted value)
        // closes the gap where a crafted POST could otherwise still move it (Bug_Report_v6.md
        // Finding 21). nextDueDate only (re)seeds from startDate on
        // create — an edit never rewinds an already-advancing cursor, so mid-life edits (e.g.
        // correcting the amount) don't accidentally trigger an extra generation.
        if (template.getId() == null) {
            template.setStartDate(form.getStartDate());
            template.setNextDueDate(form.getStartDate());
        }
    }

    /**
     * Generates one Expense per due, active template (§5.3) — called daily by
     * {@link RecurringExpenseScheduler} and on-demand by the "Generate Now" button. Idempotent:
     * a template's {@code nextDueDate} only advances once per call, so re-running against the
     * same {@code asOf} date after a template has already been brought current is a no-op.
     */
    public int generateDueExpenses(LocalDate asOf) {
        List<RecurringExpenseTemplate> due = recurringExpenseTemplateRepository.findByActiveTrueAndNextDueDateLessThanEqual(asOf);
        int generated = 0;
        for (RecurringExpenseTemplate template : due) {
            generateOne(template);
            generated++;
        }
        return generated;
    }

    /** Generates (and advances) a single template regardless of due date — backs the per-row
     *  "Generate Now" button. A no-op if the template is inactive. */
    public int generateNow(Long templateId) {
        RecurringExpenseTemplate template = getById(templateId);
        if (!template.isActive()) {
            return 0;
        }
        generateOne(template);
        return 1;
    }

    /**
     * @Version on RecurringExpenseTemplate (added for Bug_Report_v6.md Finding 6) guards this
     * against the 01:00 scheduler firing concurrently with a staff member clicking "Generate Now"
     * for the same template — saveAndFlush forces the version check immediately (rather than at
     * the enclosing transaction's commit), so a losing concurrent call fails fast here and its
     * Expense insert rolls back with it, instead of silently generating a duplicate expense for
     * the same due period. Mirrors AppointmentService.saveWithConflictCheck's identical pattern.
     */
    private void generateOne(RecurringExpenseTemplate template) {
        Expense expense = Expense.builder()
                .category(template.getCategory())
                .label(template.getLabel())
                .expenseDate(template.getNextDueDate())
                .amount(template.getDefaultAmount())
                .vendorName(template.getVendorName())
                .paymentMethod(template.getPaymentMethod())
                .status(ExpenseStatus.ACTIVE)
                .sourceTemplate(template)
                .recordedBy(template.getCreatedBy())
                .build();
        expenseRepository.save(expense);

        LocalDate nextDue = advance(template.getNextDueDate(), template.getStartDate(), template.getFrequency());
        template.setNextDueDate(nextDue);
        if (template.getEndDate() != null && nextDue.isAfter(template.getEndDate())) {
            template.setActive(false);
            log.info("Auto-deactivated recurring expense template id={} label='{}' — past end date",
                    template.getId(), template.getLabel());
        }
        try {
            recurringExpenseTemplateRepository.saveAndFlush(template);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(
                    "This recurring template was just generated by another process. Please refresh and try again.", ex);
        }
        log.info("Generated expense from recurring template id={} label='{}' amount={} date={}",
                template.getId(), template.getLabel(), expense.getAmount(), expense.getExpenseDate());
    }

    /**
     * Advances to the next due date, anchored to the template's original {@code startDate} day-of-
     * month rather than chained off {@code currentDueDate}'s own day. {@code LocalDate.plusMonths}
     * clamps to the target month's last valid day instead of overflowing — chaining off the
     * previous (already-clamped) due date would permanently pin a template due on the 29th-31st to
     * whatever shorter day it first landed on the first time it crossed a shorter month, never
     * recovering even once a long-enough month comes back around (Bug_Report_v7.md Finding 11). Only
     * {@code currentDueDate}'s year/month is used here (via {@link YearMonth}) to determine which
     * period to land in; the day-of-month is always freshly computed from {@code startDate}, clamped
     * to whatever the target month actually allows — e.g. a template anchored to the 31st correctly
     * lands on Feb 28, then recovers to Mar 31 the very next generation, rather than staying pinned
     * at the 28th forever.
     */
    private LocalDate advance(LocalDate currentDueDate, LocalDate startDate, com.clinic.healinghouse.entity.RecurrenceFrequency frequency) {
        int monthsToAdd = switch (frequency) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case YEARLY -> 12;
        };
        YearMonth targetMonth = YearMonth.from(currentDueDate).plusMonths(monthsToAdd);
        int targetDay = Math.min(startDate.getDayOfMonth(), targetMonth.lengthOfMonth());
        return targetMonth.atDay(targetDay);
    }
}
