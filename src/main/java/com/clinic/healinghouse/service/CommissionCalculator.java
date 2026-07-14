package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.TherapistEarningsDTO;
import com.clinic.healinghouse.entity.Therapist;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.AppointmentServiceLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Per-line therapist commission/bonus calculation (Requirements v1.2 §"Per-Line Therapist Attribution").
 * Marcia Gomes Yadav (owner) is excluded from all payout calculations — see {@link Therapist#isOwner()}.
 * Commission only accrues on lines whose service/product is tagged {@link #COMMISSION_TAG};
 * the bonus-threshold count only includes lines whose service is tagged {@link #BONUS_TAG}
 * (both matched case-insensitively).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommissionCalculator {

    /** Tag (case-insensitive) that marks a service/product line as commission-eligible. */
    public static final String COMMISSION_TAG = "Commission";
    /** Tag (case-insensitive) that marks a service line as counting towards the performance-bonus threshold. */
    public static final String BONUS_TAG = "Bonus";

    private final AppointmentServiceLineRepository serviceLineRepository;
    private final AppointmentProductLineRepository productLineRepository;

    public BigDecimal calculateServicesRevenue(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(
                therapist, startOf(dateFrom), endOf(dateTo), COMMISSION_TAG);
    }

    public BigDecimal calculateProductsRevenue(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return productLineRepository.sumProductRevenueByTherapistAndDateRangeAndTag(
                therapist, startOf(dateFrom), endOf(dateTo), COMMISSION_TAG);
    }

    public long calculateServicesCount(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return serviceLineRepository.countServicesPerformedByTherapistAndDateRangeAndTag(
                therapist, startOf(dateFrom), endOf(dateTo), BONUS_TAG);
    }

    /** Total services revenue for the therapist, regardless of tag — reporting only, not used in commission math. */
    public BigDecimal calculateAllServicesRevenue(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return serviceLineRepository.sumAllServiceRevenueByTherapistAndDateRange(
                therapist, startOf(dateFrom), endOf(dateTo));
    }

    /** Total products revenue for the therapist, regardless of tag — reporting only, not used in commission math. */
    public BigDecimal calculateAllProductsRevenue(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return productLineRepository.sumAllProductRevenueByTherapistAndDateRange(
                therapist, startOf(dateFrom), endOf(dateTo));
    }

    /** Total service count for the therapist, regardless of tag — reporting only, not used in bonus math. */
    public long calculateAllServicesCount(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return serviceLineRepository.countAllServicesPerformedByTherapistAndDateRange(
                therapist, startOf(dateFrom), endOf(dateTo));
    }

    /** Revenue of Bonus-tagged service lines only — reporting only, not used in commission math. */
    public BigDecimal calculateBonusTaggedServicesRevenue(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        return serviceLineRepository.sumServiceRevenueByTherapistAndDateRangeAndTag(
                therapist, startOf(dateFrom), endOf(dateTo), BONUS_TAG);
    }

    /** Bonus is all-or-nothing: the full configured amount once servicesCount meets the threshold. */
    public BigDecimal calculateBonus(Therapist therapist, long servicesCount) {
        if (therapist.isOwner()) return BigDecimal.ZERO;
        Integer threshold = therapist.getPerformanceBonusThreshold();
        BigDecimal amount = therapist.getPerformanceBonusAmount();
        if (threshold == null || amount == null || servicesCount < threshold) return BigDecimal.ZERO;
        return amount;
    }

    /** Full earnings breakdown for one therapist over a date range — the DTO every Phase 3 report renders. */
    public TherapistEarningsDTO calculateEarnings(Therapist therapist, LocalDate dateFrom, LocalDate dateTo) {
        BigDecimal fixedSalary = zeroIfNull(therapist.getFixedMonthlySalary());

        // "All" reporting figures are informational only (not payout math), so they're computed
        // for the owner too — only commission/bonus/variable-pay are skipped for her.
        if (therapist.isOwner()) {
            BigDecimal allServicesRevenue = calculateAllServicesRevenue(therapist, dateFrom, dateTo);
            BigDecimal allProductsRevenue = calculateAllProductsRevenue(therapist, dateFrom, dateTo);
            long allServicesCount = calculateAllServicesCount(therapist, dateFrom, dateTo);
            BigDecimal bonusTaggedServicesRevenue = calculateBonusTaggedServicesRevenue(therapist, dateFrom, dateTo);

            return new TherapistEarningsDTO(therapist,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                    allServicesRevenue, allProductsRevenue, allServicesCount, bonusTaggedServicesRevenue,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    false, BigDecimal.ZERO, BigDecimal.ZERO, fixedSalary);
        }

        BigDecimal servicesRevenue = calculateServicesRevenue(therapist, dateFrom, dateTo);
        BigDecimal productsRevenue = calculateProductsRevenue(therapist, dateFrom, dateTo);
        long servicesCount = calculateServicesCount(therapist, dateFrom, dateTo);

        BigDecimal allServicesRevenue = calculateAllServicesRevenue(therapist, dateFrom, dateTo);
        BigDecimal allProductsRevenue = calculateAllProductsRevenue(therapist, dateFrom, dateTo);
        long allServicesCount = calculateAllServicesCount(therapist, dateFrom, dateTo);
        BigDecimal bonusTaggedServicesRevenue = calculateBonusTaggedServicesRevenue(therapist, dateFrom, dateTo);

        BigDecimal rate = zeroIfNull(therapist.getCommissionRate());
        // Displayed separately per-category, but rounded independently they can diverge ±₹0.01 from
        // the documented single-formula total below — only totalCommission (the actual payout input)
        // uses the sum-then-round formula.
        BigDecimal serviceCommission = servicesRevenue.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal productCommission = productsRevenue.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCommission = servicesRevenue.add(productsRevenue).multiply(rate).setScale(2, RoundingMode.HALF_UP);

        BigDecimal bonusAmount = calculateBonus(therapist, servicesCount);
        boolean bonusEarned = bonusAmount.signum() > 0;

        BigDecimal totalVariablePay = totalCommission.add(bonusAmount);

        return new TherapistEarningsDTO(therapist,
                servicesRevenue, productsRevenue, servicesCount,
                allServicesRevenue, allProductsRevenue, allServicesCount, bonusTaggedServicesRevenue,
                serviceCommission, productCommission, totalCommission,
                bonusEarned, bonusAmount, totalVariablePay, fixedSalary);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static LocalDateTime startOf(LocalDate date) {
        return date.atStartOfDay();
    }

    private static LocalDateTime endOf(LocalDate date) {
        return date.atTime(LocalTime.MAX);
    }
}
