package com.clinic.healinghouse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * The first scheduled/background job in this codebase (requirements/Expenses_Requirements_v1.md
 * §5.3, §10). Runs daily at 01:00 — no explicit {@code zone=} needed since {@code
 * HealinghouseApplication.main} already forces the JVM default timezone to Asia/Kolkata, so this
 * cron expression fires at 01:00 IST wall-clock time, not UTC. Do not "fix" this by adding a zone
 * attribute without also reconsidering that global timezone forcing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringExpenseScheduler {

    private final RecurringExpenseTemplateService recurringExpenseTemplateService;

    @Scheduled(cron = "0 0 1 * * *")
    public void generateDueExpenses() {
        int generated = recurringExpenseTemplateService.generateDueExpenses(LocalDate.now());
        if (generated > 0) {
            log.info("Recurring expense scheduler generated {} expense(s).", generated);
        }
    }
}
