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

    private String notes;
    private String paymentMethod;   // kept as String to avoid enum binding errors on empty selection
    private BigDecimal amountPaid = BigDecimal.ZERO;

    private List<ServiceLineForm> serviceLines = new ArrayList<>();
    private List<ProductLineForm> productLines  = new ArrayList<>();

    /** Pre-populate form fields from an existing appointment (used in edit flow). */
    public static AppointmentForm from(Appointment appt) {
        AppointmentForm f = new AppointmentForm();
        f.setPatientId(appt.getPatient().getId());
        f.setTherapistId(appt.getTherapist().getId());
        f.setAppointmentDateTime(appt.getAppointmentDateTime());
        f.setNotes(appt.getNotes());
        f.setPaymentMethod(appt.getPaymentMethod() != null ? appt.getPaymentMethod().name() : null);
        f.setAmountPaid(appt.getAmountPaid() != null ? appt.getAmountPaid() : BigDecimal.ZERO);
        appt.getServiceLines().forEach(sl -> {
            ServiceLineForm s = new ServiceLineForm();
            s.setServiceId(sl.getService().getId());
            s.setQuantity(sl.getQuantity());
            f.getServiceLines().add(s);
        });
        appt.getProductLines().forEach(pl -> {
            ProductLineForm p = new ProductLineForm();
            p.setProductId(pl.getProduct().getId());
            p.setQuantity(pl.getQuantity());
            f.getProductLines().add(p);
        });
        return f;
    }

    @Data
    public static class ServiceLineForm {
        private Long serviceId;
        private int  quantity = 1;
    }

    @Data
    public static class ProductLineForm {
        private Long productId;
        private int  quantity = 1;
    }
}
