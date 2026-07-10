package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentProductLine;
import com.clinic.healinghouse.entity.AppointmentServiceLine;
import com.clinic.healinghouse.entity.AppointmentStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

    /** Matches appointments where the given therapist is the main therapist OR performed/sold any line item. */
    public static Specification<Appointment> hasTherapistId(Long therapistId) {
        return (root, query, cb) -> {
            if (therapistId == null) return cb.conjunction();

            Subquery<Long> serviceLineSub = query.subquery(Long.class);
            Root<AppointmentServiceLine> slRoot = serviceLineSub.from(AppointmentServiceLine.class);
            serviceLineSub.select(slRoot.get("appointment").get("id"))
                    .where(cb.equal(slRoot.get("therapist").get("id"), therapistId));

            Subquery<Long> productLineSub = query.subquery(Long.class);
            Root<AppointmentProductLine> plRoot = productLineSub.from(AppointmentProductLine.class);
            productLineSub.select(plRoot.get("appointment").get("id"))
                    .where(cb.equal(plRoot.get("therapist").get("id"), therapistId));

            return cb.or(
                    cb.equal(root.get("therapist").get("id"), therapistId),
                    root.get("id").in(serviceLineSub),
                    root.get("id").in(productLineSub));
        };
    }

    public static Specification<Appointment> hasPatientId(Long patientId) {
        return (root, query, cb) ->
                patientId == null ? cb.conjunction()
                        : cb.equal(root.get("patient").get("id"), patientId);
    }

    /** Matches appointments whose patient's full name OR phone contains the given text. */
    public static Specification<Appointment> patientNameOrPhoneContains(String text) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(text)) return cb.conjunction();
            String pattern = "%" + text.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("patient").get("fullName")), pattern),
                    cb.like(cb.lower(root.get("patient").get("phone")), pattern));
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