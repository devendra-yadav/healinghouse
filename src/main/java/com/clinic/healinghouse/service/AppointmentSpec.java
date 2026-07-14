package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentCombo;
import com.clinic.healinghouse.entity.AppointmentProductLine;
import com.clinic.healinghouse.entity.AppointmentServiceLine;
import com.clinic.healinghouse.entity.AppointmentStatus;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    public static Specification<Appointment> hasStatusIn(List<AppointmentStatus> statuses) {
        return (root, query, cb) ->
                (statuses == null || statuses.isEmpty()) ? cb.conjunction() : root.get("status").in(statuses);
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

    public static Specification<Appointment> hasPaymentMethod(PaymentMethod paymentMethod) {
        return (root, query, cb) ->
                paymentMethod == null ? cb.conjunction() : cb.equal(root.get("paymentMethod"), paymentMethod);
    }

    /** Matches appointments with any service line for the given ClinicService (standalone or combo line). */
    public static Specification<Appointment> hasServiceId(Long serviceId) {
        return (root, query, cb) -> {
            if (serviceId == null) return cb.conjunction();

            Subquery<Long> sub = query.subquery(Long.class);
            Root<AppointmentServiceLine> slRoot = sub.from(AppointmentServiceLine.class);
            sub.select(slRoot.get("appointment").get("id"))
                    .where(cb.equal(slRoot.get("service").get("id"), serviceId));

            return root.get("id").in(sub);
        };
    }

    /** Matches appointments with any product line for the given Product (standalone or combo line). */
    public static Specification<Appointment> hasProductId(Long productId) {
        return (root, query, cb) -> {
            if (productId == null) return cb.conjunction();

            Subquery<Long> sub = query.subquery(Long.class);
            Root<AppointmentProductLine> plRoot = sub.from(AppointmentProductLine.class);
            sub.select(plRoot.get("appointment").get("id"))
                    .where(cb.equal(plRoot.get("product").get("id"), productId));

            return root.get("id").in(sub);
        };
    }

    /** Matches appointments with any line whose service or product carries the given tag (case-insensitive). */
    public static Specification<Appointment> hasTagName(String tagName) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(tagName)) return cb.conjunction();
            String lowerTag = tagName.trim().toLowerCase();

            Subquery<Long> serviceTagSub = query.subquery(Long.class);
            Root<AppointmentServiceLine> slRoot = serviceTagSub.from(AppointmentServiceLine.class);
            Join<AppointmentServiceLine, ClinicService> serviceJoin = slRoot.join("service");
            Join<ClinicService, Tag> serviceTagJoin = serviceJoin.join("tags");
            serviceTagSub.select(slRoot.get("appointment").get("id"))
                    .where(cb.equal(cb.lower(serviceTagJoin.get("name")), lowerTag));

            Subquery<Long> productTagSub = query.subquery(Long.class);
            Root<AppointmentProductLine> plRoot = productTagSub.from(AppointmentProductLine.class);
            Join<AppointmentProductLine, Product> productJoin = plRoot.join("product");
            Join<Product, Tag> productTagJoin = productJoin.join("tags");
            productTagSub.select(plRoot.get("appointment").get("id"))
                    .where(cb.equal(cb.lower(productTagJoin.get("name")), lowerTag));

            return cb.or(root.get("id").in(serviceTagSub), root.get("id").in(productTagSub));
        };
    }

    /** Matches appointments with a positive whole-appointment discount, or at least one combo with a positive discount. */
    public static Specification<Appointment> isDiscountedOnly(boolean discountedOnly) {
        return (root, query, cb) -> {
            if (!discountedOnly) return cb.conjunction();

            Subquery<Long> comboSub = query.subquery(Long.class);
            Root<AppointmentCombo> acRoot = comboSub.from(AppointmentCombo.class);
            comboSub.select(acRoot.get("appointment").get("id"))
                    .where(cb.greaterThan(acRoot.get("discountAmount"), BigDecimal.ZERO));

            return cb.or(
                    cb.greaterThan(root.get("discountAmount"), BigDecimal.ZERO),
                    root.get("id").in(comboSub));
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