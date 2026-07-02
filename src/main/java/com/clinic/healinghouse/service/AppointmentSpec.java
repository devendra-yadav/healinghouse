package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentStatus;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Reusable JPA Specifications for dynamic Appointment list filtering.
 * Combine with Specification.where(...).and(...) in AppointmentService.
 */
public class AppointmentSpec {

    private AppointmentSpec() {}

    public static Specification<Appointment> hasStatus(AppointmentStatus status) {
        return (root, query, cb) ->
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    public static Specification<Appointment> hasTherapistId(Long therapistId) {
        return (root, query, cb) ->
                therapistId == null ? cb.conjunction()
                        : cb.equal(root.get("therapist").get("id"), therapistId);
    }

    public static Specification<Appointment> patientNameContains(String name) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(name)) return cb.conjunction();
            return cb.like(cb.lower(root.get("patient").get("fullName")),
                           "%" + name.trim().toLowerCase() + "%");
        };
    }

    public static Specification<Appointment> betweenDates(LocalDateTime start, LocalDateTime end) {
        return (root, query, cb) -> {
            if (start == null && end == null) return cb.conjunction();
            if (start == null) return cb.lessThanOrEqualTo(root.get("appointmentDateTime"), end);
            if (end == null)   return cb.greaterThanOrEqualTo(root.get("appointmentDateTime"), start);
            return cb.between(root.get("appointmentDateTime"), start, end);
        };
    }

    /** Eagerly fetches patient and therapist to avoid N+1 on list pages. */
    public static Specification<Appointment> withPatientAndTherapist() {
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType()) {
                root.fetch("patient",   JoinType.LEFT);
                root.fetch("therapist", JoinType.LEFT);
                query.distinct(true);
            }
            return cb.conjunction();
        };
    }
}