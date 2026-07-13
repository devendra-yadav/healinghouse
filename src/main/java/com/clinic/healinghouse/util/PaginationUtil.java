package com.clinic.healinghouse.util;

/** Shared page-size bounds for list pages that let staff choose how many rows to view per page. */
public final class PaginationUtil {

    public static final int MAX_PAGE_SIZE = 100;

    private PaginationUtil() {}

    public static int clampPageSize(int requested) {
        return Math.min(Math.max(requested, 1), MAX_PAGE_SIZE);
    }
}
