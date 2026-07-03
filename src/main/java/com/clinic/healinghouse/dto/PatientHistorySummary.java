package com.clinic.healinghouse.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Lifetime aggregate stats for a patient, shown above the appointment history table. */
@Data
@Builder
public class PatientHistorySummary {

    @Builder.Default private int totalAppointments = 0;
    @Builder.Default private int completedCount    = 0;
    @Builder.Default private int cancelledCount    = 0;
    @Builder.Default private int noShowCount       = 0;

    @Builder.Default private BigDecimal totalRevenue     = BigDecimal.ZERO;
    @Builder.Default private BigDecimal totalPaid        = BigDecimal.ZERO;
    @Builder.Default private BigDecimal totalOutstanding = BigDecimal.ZERO;

    private LocalDateTime lastVisitDate;

    private String mostSeenTherapistName;
    @Builder.Default private int mostSeenTherapistCount = 0;

    private String topServiceName;
    @Builder.Default private int topServiceCount = 0;

    private String topProductName;
    @Builder.Default private int topProductCount = 0;

    @Builder.Default private BigDecimal currentMonthSpend = BigDecimal.ZERO;
    @Builder.Default private BigDecimal currentYearSpend  = BigDecimal.ZERO;
}
