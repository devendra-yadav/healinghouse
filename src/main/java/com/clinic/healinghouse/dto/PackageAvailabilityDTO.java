package com.clinic.healinghouse.dto;

/**
 * One pooled, ready-to-consume entry for the appointment form's "Already Paid" section: either a
 * service or a product (exactly one of serviceId/productId is non-null), the pooled remaining
 * session count summed across every eligible purchase, and nextItemId — the specific
 * PatientPackageServiceItem/ProductItem FIFO would draw from right now. The client round-trips
 * nextItemId back as packageItemId; the server re-validates it fresh at save time (never trusts it
 * blindly) — see AppointmentService's line-building integration.
 */
public record PackageAvailabilityDTO(Long serviceId, Long productId, String name,
                                      int sessionsRemaining, Long nextItemId) {}
