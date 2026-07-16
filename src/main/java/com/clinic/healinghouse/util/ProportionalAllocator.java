package com.clinic.healinghouse.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Splits an amount proportionally across a set of lines, by each line's share of a basis subtotal.
 * Extracted from AppointmentService's discount/combo distribution so PackageService can reuse the
 * same fair-split algorithm to divide a package sale's totalPrice across its items (§5.2/§10 of
 * Packages_Requirements_v1.md) without duplicating it.
 */
public final class ProportionalAllocator {

    private ProportionalAllocator() {}

    public record AllocationLine(BigDecimal rawAmount, Consumer<BigDecimal> setter) {}

    /**
     * Lines are processed smallest-raw-amount first; the last (largest) line absorbs whatever
     * rounding remainder is left, so the per-line shares always sum exactly to `amount`. Each
     * line's setter receives rawAmount - share (i.e. the amount *after* the allocated share is
     * removed — e.g. a discounted line total, or this line's price contribution net of what it
     * gave up). Callers that want the share itself rather than the remainder should pass a setter
     * that inverts this (see PackageService.sellPackage for the priceAllocated case).
     */
    public static void distribute(List<AllocationLine> lines, BigDecimal basisSubtotal, BigDecimal amount) {
        if (lines.isEmpty()) return;
        if (basisSubtotal.signum() <= 0) {
            // Nothing to proportion against (e.g. every line in the basis is ₹0) — leave every
            // line at its raw amount rather than dividing by zero.
            lines.forEach(l -> l.setter().accept(l.rawAmount()));
            return;
        }
        lines.sort(Comparator.comparing(AllocationLine::rawAmount));

        BigDecimal[] shares = new BigDecimal[lines.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size() - 1; i++) {
            BigDecimal lineRaw = lines.get(i).rawAmount();
            BigDecimal share = amount.multiply(lineRaw)
                    .divide(basisSubtotal, 10, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP)
                    .min(lineRaw); // a line's own share can never rationally exceed its own raw amount
            shares[i] = share;
            allocated = allocated.add(share);
        }

        // The last (largest) line absorbs whatever remainder is left so the total always sums exactly
        // to `amount` — but independently HALF_UP-rounding every earlier share can over-allocate by up
        // to ~0.005 each, so with enough lines and a small `amount` that remainder can go negative or,
        // in principle, exceed the line's own raw amount. Clamp it to [0, lineRaw] like every other
        // line, then walk the leftover from clamping backward through the earlier lines (nudging their
        // shares up or down within their own [0, lineRaw] bounds) until it's fully absorbed, so the
        // exact-sum invariant still holds.
        int lastIdx = lines.size() - 1;
        BigDecimal lastRaw = lines.get(lastIdx).rawAmount();
        BigDecimal lastShare = amount.subtract(allocated);
        BigDecimal clampedLastShare = lastShare.max(BigDecimal.ZERO).min(lastRaw);
        shares[lastIdx] = clampedLastShare;
        BigDecimal leftover = lastShare.subtract(clampedLastShare);

        for (int i = lastIdx - 1; i >= 0 && leftover.signum() != 0; i--) {
            BigDecimal room = leftover.signum() > 0
                    ? lines.get(i).rawAmount().subtract(shares[i]) // room to raise this line's share
                    : shares[i];                                   // room to lower this line's share
            BigDecimal adjust = leftover.abs().min(room);
            if (adjust.signum() <= 0) continue;
            shares[i] = leftover.signum() > 0 ? shares[i].add(adjust) : shares[i].subtract(adjust);
            leftover = leftover.signum() > 0 ? leftover.subtract(adjust) : leftover.add(adjust);
        }

        for (int i = 0; i < lines.size(); i++) {
            AllocationLine line = lines.get(i);
            line.setter().accept(line.rawAmount().subtract(shares[i]));
        }
    }
}
