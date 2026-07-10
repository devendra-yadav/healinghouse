package com.clinic.healinghouse.dto;

import com.clinic.healinghouse.entity.Appointment;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Form-backing DTO for appointment create/edit.
 * Line items are sent as indexed form params:
 *   serviceLines[0].serviceId, serviceLines[0].quantity, …
 *   productLines[0].productId, productLines[0].quantity, …
 */
@Data
public class AppointmentForm {

    private Long patientId;
    private Long therapistId;

    // datetime-local input sends "yyyy-MM-dd'T'HH:mm"
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime appointmentDateTime;

    private Integer durationMinutes = 60;

    private String notes;
    private String paymentMethod;   // kept as String to avoid enum binding errors on empty selection

    private String discountType;    // "NONE" | "PERCENTAGE" | "FLAT" — kept as String, same reason as paymentMethod
    /** Percentage (0-100) or flat rupee amount, per discountType. Null/0 = no discount. */
    private BigDecimal discountValue;

    /** Payment being entered in this submission — added on top of any existing amount paid (edit mode) or the whole initial payment (create mode). */
    private BigDecimal newPaymentAmount = BigDecimal.ZERO;

    /** Only set when the "correct prepaid amount" pencil was used in edit mode; null means keep the existing stored amount. */
    private BigDecimal prepaidCorrection;

    private List<ServiceLineForm> serviceLines = new ArrayList<>();
    private List<ProductLineForm> productLines  = new ArrayList<>();

    /** Pre-populate form fields from an existing appointment (used in edit flow). */
    public static AppointmentForm from(Appointment appt) {
        AppointmentForm f = new AppointmentForm();
        f.setPatientId(appt.getPatient().getId());
        f.setTherapistId(appt.getTherapist().getId());
        f.setAppointmentDateTime(appt.getAppointmentDateTime());
        f.setDurationMinutes(appt.getDurationMinutes());
        f.setNotes(appt.getNotes());
        f.setPaymentMethod(appt.getPaymentMethod() != null ? appt.getPaymentMethod().name() : null);
        f.setDiscountType(appt.getDiscountType() != null ? appt.getDiscountType().name() : "NONE");
        f.setDiscountValue(appt.getDiscountValue());
        // newPaymentAmount defaults to 0 (nothing entered yet); prepaidCorrection stays null (no correction).
        appt.getServiceLines().forEach(sl -> {
            ServiceLineForm s = new ServiceLineForm();
            s.setServiceId(sl.getService().getId());
            s.setQuantity(sl.getQuantity());
            s.setTherapistId(sl.getTherapist().getId());
            f.getServiceLines().add(s);
        });
        appt.getProductLines().forEach(pl -> {
            ProductLineForm p = new ProductLineForm();
            p.setProductId(pl.getProduct().getId());
            p.setQuantity(pl.getQuantity());
            p.setTherapistId(pl.getTherapist().getId());
            f.getProductLines().add(p);
        });
        return f;
    }

    @Data
    public static class ServiceLineForm {
        private Long serviceId;
        private int  quantity = 1;
        /** Null means "use the appointment's main therapist" — resolved server-side. */
        private Long therapistId;
    }

    @Data
    public static class ProductLineForm {
        private Long productId;
        private int  quantity = 1;
        /** Null means "use the appointment's main therapist" — resolved server-side. */
        private Long therapistId;
    }
}
