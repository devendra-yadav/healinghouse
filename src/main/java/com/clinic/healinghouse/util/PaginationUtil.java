package com.clinic.healinghouse.util;

import com.clinic.healinghouse.config.HealingHouseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared page-size bounds for list pages that let staff choose how many rows to view per page. */
@Component
@RequiredArgsConstructor
public class PaginationUtil {

    private final HealingHouseProperties properties;

    public int clampPageSize(int requested) {
        return Math.min(Math.max(requested, 1), properties.getPagination().getMaxPageSize());
    }

    public int clampPage(int requested) {
        return Math.max(requested, 0);
    }
}
