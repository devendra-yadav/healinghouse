package com.clinic.healinghouse.util;

import java.util.List;

/**
 * Deterministic therapistId -> color assignment for the all-therapists calendar, so a therapist's
 * color is stable across page loads and agrees between the calendar events and the checkbox-list
 * legend swatches, without any admin-facing color picker or new persisted column.
 */
public final class TherapistColorUtil {

    private TherapistColorUtil() {}

    private static final List<String> PALETTE = List.of(
            "#0d6efd", // blue
            "#e8590c", // orange
            "#20c997", // teal
            "#d63384", // pink
            "#6f42c1", // purple
            "#fd7e14", // amber
            "#0dcaf0", // cyan
            "#c2410c", // rust
            "#198754", // green
            "#6610f2"  // indigo
    );

    public static String colorFor(Long therapistId) {
        int index = Math.floorMod(therapistId.hashCode(), PALETTE.size());
        return PALETTE.get(index);
    }
}
