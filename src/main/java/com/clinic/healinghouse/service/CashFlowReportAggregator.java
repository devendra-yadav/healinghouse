package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.CashFlowByPaymentMethodDTO;
import com.clinic.healinghouse.dto.CashFlowReportDTO;
import com.clinic.healinghouse.dto.CashFlowSummaryDTO;
import com.clinic.healinghouse.dto.CashFlowTrendPointDTO;
import com.clinic.healinghouse.dto.CashLedgerEntryDTO;
import com.clinic.healinghouse.entity.AppointmentPaymentTransaction;
import com.clinic.healinghouse.entity.AppointmentPaymentTransactionType;
import com.clinic.healinghouse.entity.Expense;
import com.clinic.healinghouse.entity.ExpenseStatus;
import com.clinic.healinghouse.entity.PackageTransaction;
import com.clinic.healinghouse.entity.PackageTransactionType;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.entity.WalletTransactionType;
import com.clinic.healinghouse.repository.AppointmentPaymentTransactionRepository;
import com.clinic.healinghouse.repository.ExpenseRepository;
import com.clinic.healinghouse.repository.PackageTransactionRepository;
import com.clinic.healinghouse.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Cash Flow report (/reports/cash-flow): real money in vs. real money out, on a strict
 * cash basis — as opposed to every other report's accrual basis (Appointment.grandTotal at
 * COMPLETED). Reads four independent ledgers and never double-counts across them:
 * <ul>
 *   <li>Inflow: AppointmentPaymentTransaction (fresh cash/UPI/card/bank on an appointment),
 *       PackageTransaction.PURCHASE, WalletTransaction.TOP_UP</li>
 *   <li>Outflow: Expense (ACTIVE), WalletTransaction.REFUND, PackageTransaction.REFUND, and any
 *       negative AppointmentPaymentTransaction flagged {@code cashPhysicallyReturned} (a downward
 *       prepaid-pencil-edit correction where staff confirmed cash was actually handed back — see
 *       that field's javadoc). A downward correction NOT so flagged is treated as a data-entry fix,
 *       not a real cash-flow event, and is excluded from every total/ledger entry entirely.</li>
 * </ul>
 * Deliberately excluded: WalletTransaction.USAGE/REVERSAL and PackageTransaction.USAGE/REVERSAL —
 * internal transfers between "wallet/package balance" and "amount owed on an appointment," no money
 * moves. A wallet-top-up-funded or package-funded appointment line is therefore never double-counted:
 * it was already recognized as inflow at top-up/purchase time, and AppointmentPaymentTransaction only
 * ever records the fresh-cash delta on top of that (see AppointmentService's ledger-writing comments).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashFlowReportAggregator {

    private final AppointmentPaymentTransactionRepository appointmentPaymentTransactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PackageTransactionRepository packageTransactionRepository;
    private final ExpenseRepository expenseRepository;
    private final HealingHouseProperties properties;

    public CashFlowReportDTO getCashFlowReport(LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        LocalDateTime start = dateFrom.atStartOfDay();
        LocalDateTime end = dateTo.atTime(LocalTime.MAX);

        List<AppointmentPaymentTransaction> appointmentPayments =
                appointmentPaymentTransactionRepository.findByCreatedAtBetween(start, end);
        List<WalletTransaction> walletTxns = walletTransactionRepository.findByTypeInAndCreatedAtBetween(
                List.of(WalletTransactionType.TOP_UP, WalletTransactionType.REFUND), start, end);
        List<PackageTransaction> packageTxns = packageTransactionRepository.findByTypeInAndCreatedAtBetween(
                List.of(PackageTransactionType.PURCHASE, PackageTransactionType.REFUND), start, end);
        List<Expense> expenses = expenseRepository.findByStatusAndExpenseDateBetween(ExpenseStatus.ACTIVE, dateFrom, dateTo);

        CashFlowSummaryDTO summary = buildSummary(appointmentPayments, walletTxns, packageTxns, expenses);
        List<CashFlowByPaymentMethodDTO> byPaymentMethod = buildByPaymentMethod(appointmentPayments, walletTxns, packageTxns);
        List<CashFlowTrendPointDTO> trend = buildTrend(dateFrom, dateTo, appointmentPayments, walletTxns, packageTxns, expenses);
        Page<CashLedgerEntryDTO> ledger = buildLedger(appointmentPayments, walletTxns, packageTxns, expenses, pageable);

        return new CashFlowReportDTO(dateFrom, dateTo, summary, byPaymentMethod, trend, ledger);
    }

    private CashFlowSummaryDTO buildSummary(List<AppointmentPaymentTransaction> appointmentPayments,
                                             List<WalletTransaction> walletTxns,
                                             List<PackageTransaction> packageTxns,
                                             List<Expense> expenses) {
        BigDecimal appointmentCashInflow = BigDecimal.ZERO;
        BigDecimal appointmentPaymentCorrections = BigDecimal.ZERO;
        for (AppointmentPaymentTransaction t : appointmentPayments) {
            if (t.getAmount().signum() >= 0) {
                appointmentCashInflow = appointmentCashInflow.add(t.getAmount());
            } else if (t.isCashPhysicallyReturned()) {
                appointmentPaymentCorrections = appointmentPaymentCorrections.add(t.getAmount().abs());
            }
            // else: a downward correction fixing a data-entry mistake — no real cash moved, excluded.
        }

        BigDecimal walletTopUps = sumByType(walletTxns, WalletTransactionType.TOP_UP, WalletTransaction::getType, WalletTransaction::getAmount);
        BigDecimal walletRefunds = sumByType(walletTxns, WalletTransactionType.REFUND, WalletTransaction::getType, WalletTransaction::getAmount);
        BigDecimal packageSales = sumByType(packageTxns, PackageTransactionType.PURCHASE, PackageTransaction::getType, PackageTransaction::getAmount);
        BigDecimal packageRefunds = sumByType(packageTxns, PackageTransactionType.REFUND, PackageTransaction::getType, PackageTransaction::getAmount);

        BigDecimal expenseTotal = expenses.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInflow = appointmentCashInflow.add(packageSales).add(walletTopUps);
        BigDecimal totalOutflow = expenseTotal.add(walletRefunds).add(packageRefunds).add(appointmentPaymentCorrections);

        return new CashFlowSummaryDTO(appointmentCashInflow, packageSales, walletTopUps, totalInflow,
                expenseTotal, walletRefunds, packageRefunds, appointmentPaymentCorrections, totalOutflow,
                totalInflow.subtract(totalOutflow));
    }

    private <T, E> BigDecimal sumByType(List<T> items, E wantedType, java.util.function.Function<T, E> typeFn,
                                         java.util.function.Function<T, BigDecimal> amountFn) {
        BigDecimal total = BigDecimal.ZERO;
        for (T item : items) {
            if (typeFn.apply(item).equals(wantedType)) {
                total = total.add(amountFn.apply(item));
            }
        }
        return total;
    }

    /** All real inflow, regardless of source, grouped by the actual recorded PaymentMethod — the
     *  cash-reconciliation view of "how much did we take in via each method today," not a
     *  per-source breakdown (Package Sale / Wallet Top-up already show as their own summary cards). */
    private List<CashFlowByPaymentMethodDTO> buildByPaymentMethod(List<AppointmentPaymentTransaction> appointmentPayments,
                                                                    List<WalletTransaction> walletTxns,
                                                                    List<PackageTransaction> packageTxns) {
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (AppointmentPaymentTransaction t : appointmentPayments) {
            if (t.getAmount().signum() > 0) {
                byMethod.merge(methodLabel(t.getPaymentMethod()), t.getAmount(), BigDecimal::add);
            }
        }
        for (WalletTransaction t : walletTxns) {
            if (t.getType() == WalletTransactionType.TOP_UP) {
                byMethod.merge(methodLabel(t.getPaymentMethod()), t.getAmount(), BigDecimal::add);
            }
        }
        for (PackageTransaction t : packageTxns) {
            if (t.getType() == PackageTransactionType.PURCHASE) {
                byMethod.merge(methodLabel(t.getPaymentMethod()), t.getAmount(), BigDecimal::add);
            }
        }

        List<CashFlowByPaymentMethodDTO> result = new ArrayList<>();
        byMethod.forEach((label, amount) -> result.add(new CashFlowByPaymentMethodDTO(label, amount)));
        result.sort(Comparator.comparing(CashFlowByPaymentMethodDTO::amount).reversed());
        return result;
    }

    private static String methodLabel(PaymentMethod method) {
        return method != null ? method.name() : "Unspecified";
    }

    private List<CashFlowTrendPointDTO> buildTrend(LocalDate dateFrom, LocalDate dateTo,
                                         List<AppointmentPaymentTransaction> appointmentPayments,
                                         List<WalletTransaction> walletTxns,
                                         List<PackageTransaction> packageTxns,
                                         List<Expense> expenses) {
        Map<LocalDate, BigDecimal> inflowByDay = new HashMap<>();
        Map<LocalDate, BigDecimal> outflowByDay = new HashMap<>();

        for (AppointmentPaymentTransaction t : appointmentPayments) {
            LocalDate day = t.getCreatedAt().toLocalDate();
            if (t.getAmount().signum() >= 0) {
                inflowByDay.merge(day, t.getAmount(), BigDecimal::add);
            } else if (t.isCashPhysicallyReturned()) {
                outflowByDay.merge(day, t.getAmount().abs(), BigDecimal::add);
            }
        }
        for (WalletTransaction t : walletTxns) {
            LocalDate day = t.getCreatedAt().toLocalDate();
            if (t.getType() == WalletTransactionType.TOP_UP) {
                inflowByDay.merge(day, t.getAmount(), BigDecimal::add);
            } else {
                outflowByDay.merge(day, t.getAmount(), BigDecimal::add);
            }
        }
        for (PackageTransaction t : packageTxns) {
            LocalDate day = t.getCreatedAt().toLocalDate();
            if (t.getType() == PackageTransactionType.PURCHASE) {
                inflowByDay.merge(day, t.getAmount(), BigDecimal::add);
            } else {
                outflowByDay.merge(day, t.getAmount(), BigDecimal::add);
            }
        }
        for (Expense e : expenses) {
            outflowByDay.merge(e.getExpenseDate(), e.getAmount(), BigDecimal::add);
        }

        List<CashFlowTrendPointDTO> trend = new ArrayList<>();
        DateTimeFormatter trendLabelFormat = DateTimeFormatter.ofPattern(properties.getReports().getTrendLabelFormat());
        for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
            BigDecimal dayInflow = inflowByDay.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal dayOutflow = outflowByDay.getOrDefault(day, BigDecimal.ZERO);
            trend.add(new CashFlowTrendPointDTO(day.format(trendLabelFormat), dayInflow, dayOutflow, dayInflow.subtract(dayOutflow)));
        }
        return trend;
    }

    private Page<CashLedgerEntryDTO> buildLedger(List<AppointmentPaymentTransaction> appointmentPayments,
                                                  List<WalletTransaction> walletTxns,
                                                  List<PackageTransaction> packageTxns,
                                                  List<Expense> expenses,
                                                  Pageable pageable) {
        List<CashLedgerEntryDTO> entries = new ArrayList<>();

        for (AppointmentPaymentTransaction t : appointmentPayments) {
            boolean isOutflow = t.getAmount().signum() < 0;
            if (isOutflow && !t.isCashPhysicallyReturned()) {
                continue; // data-entry-fix correction — no real cash movement, excluded from the ledger
            }
            // Label reflects the actual transaction type, not just the amount's sign — an upward
            // CORRECTED entry (e.g. fixing a previously under-recorded payment) is still a
            // correction, not a fresh "Appointment Payment", even though both are inflow
            // (Bug_Report_v7.md Finding 16 — the type field was previously write-only, never
            // consulted, letting a positive CORRECTED row be mislabeled here).
            String label = t.getType() == AppointmentPaymentTransactionType.CORRECTED
                    ? "Appointment Correction" : "Appointment Payment";
            entries.add(new CashLedgerEntryDTO(t.getCreatedAt(),
                    label,
                    isOutflow ? "OUT" : "IN",
                    t.getPatient().getFullName(),
                    t.getAmount().abs(),
                    t.getPaymentMethod()));
        }
        for (WalletTransaction t : walletTxns) {
            boolean isTopUp = t.getType() == WalletTransactionType.TOP_UP;
            entries.add(new CashLedgerEntryDTO(t.getCreatedAt(),
                    isTopUp ? "Wallet Top-up" : "Wallet Refund",
                    isTopUp ? "IN" : "OUT",
                    t.getPatient().getFullName(),
                    t.getAmount(),
                    t.getPaymentMethod()));
        }
        for (PackageTransaction t : packageTxns) {
            boolean isPurchase = t.getType() == PackageTransactionType.PURCHASE;
            entries.add(new CashLedgerEntryDTO(t.getCreatedAt(),
                    isPurchase ? "Package Sale" : "Package Refund",
                    isPurchase ? "IN" : "OUT",
                    t.getPatientPackage().getPatient().getFullName(),
                    t.getAmount(),
                    t.getPaymentMethod()));
        }
        for (Expense e : expenses) {
            entries.add(new CashLedgerEntryDTO(e.getExpenseDate().atStartOfDay(),
                    "Expense",
                    "OUT",
                    e.getCategory().getName() + (e.getLabel() != null && !e.getLabel().isBlank() ? " — " + e.getLabel() : ""),
                    e.getAmount(),
                    e.getPaymentMethod()));
        }

        entries.sort(Comparator.comparing(CashLedgerEntryDTO::date).reversed());

        if (!pageable.isPaged()) {
            return new PageImpl<>(entries, pageable, entries.size());
        }
        int start = (int) Math.min(pageable.getOffset(), entries.size());
        int end = Math.min(start + pageable.getPageSize(), entries.size());
        return new PageImpl<>(entries.subList(start, end), pageable, entries.size());
    }
}
