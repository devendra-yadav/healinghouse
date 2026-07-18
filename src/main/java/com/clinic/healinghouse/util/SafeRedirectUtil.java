package com.clinic.healinghouse.util;

/**
 * Same-origin-only guard for client-supplied redirect targets (the {@code returnUrl} param used
 * by {@code AppointmentController}/{@code WalletController}) — mirrors the check
 * {@code GlobalExceptionHandler.fallbackUrl} already applies to the {@code Referer} header, closing
 * the same open-redirect class via this separate code path (Bug_Report_v5.md #2).
 */
public final class SafeRedirectUtil {

    private SafeRedirectUtil() {}

    /** Returns {@code url} if it's a safe same-app relative path, else {@code fallback}. */
    public static String sanitize(String url, String fallback) {
        return isSafeRelativePath(url) ? url : fallback;
    }

    private static boolean isSafeRelativePath(String url) {
        if (url == null || url.isBlank() || !url.startsWith("/")) {
            return false;
        }
        // "//evil.com" and "/\evil.com" are both browser-interpreted as protocol-relative URLs,
        // i.e. an off-app redirect despite starting with a slash.
        return !url.startsWith("//") && !url.startsWith("/\\");
    }
}
