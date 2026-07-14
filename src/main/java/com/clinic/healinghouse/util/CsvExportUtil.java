package com.clinic.healinghouse.util;

import com.clinic.healinghouse.dto.*;
import com.opencsv.CSVWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CsvExportUtil {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String generateDailyReportCsv(DailyReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Daily Report - " + report.date().format(DATE_FORMATTER));
            writePeriodSummary(writer, report.summary());
            writeTherapistEarnings(writer, report.therapistEarnings());
        }
        return sw.toString();
    }

    public static String generatePeriodReportCsv(PeriodReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Period Report - " + report.dateFrom().format(DATE_FORMATTER) +
                    " to " + report.dateTo().format(DATE_FORMATTER));
            writePeriodSummary(writer, report.summary());

            writer.writeNext(new String[]{});
            writer.writeNext(new String[]{"Therapist Earnings"});
            writeTherapistEarnings(writer, report.therapistEarnings());

            if (report.tagRevenue() != null && !report.tagRevenue().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Tag Revenue Breakdown"});
                writeTagRevenue(writer, report.tagRevenue());
            }

            if (report.productPerformance() != null && !report.productPerformance().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Product Performance"});
                writeProductPerformance(writer, report.productPerformance());
            }
        }
        return sw.toString();
    }

    public static String generateComparisonReportCsv(ComparisonReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Therapist Comparison - " + report.dateFrom().format(DATE_FORMATTER) +
                    " to " + report.dateTo().format(DATE_FORMATTER));
            writeTherapistEarnings(writer, report.therapistEarnings());
        }
        return sw.toString();
    }

    public static String generatePatientReportCsv(PatientReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Patient Acquisition Report - " + report.dateFrom().format(DATE_FORMATTER) +
                    " to " + report.dateTo().format(DATE_FORMATTER));

            writer.writeNext(new String[]{"Summary"});
            writer.writeNext(new String[]{
                    "New Patients", String.valueOf(report.totalNewPatients()),
                    "Repeat Patients", String.valueOf(report.totalRepeatPatients()),
                    "Overall Retention Rate", formatPercentage(report.overallRetentionRate())
            });
            writer.writeNext(new String[]{});

            if (report.therapistMetrics() != null && !report.therapistMetrics().isEmpty()) {
                writer.writeNext(new String[]{"Therapist Patient Metrics"});
                writeTherapistPatientMetrics(writer, report.therapistMetrics());
            }
        }
        return sw.toString();
    }

    public static String generatePerformanceReportCsv(PerformanceReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Product/Service Performance - " + report.dateFrom().format(DATE_FORMATTER) +
                    " to " + report.dateTo().format(DATE_FORMATTER));

            if (report.services() != null && !report.services().isEmpty()) {
                writer.writeNext(new String[]{"Service Performance"});
                writeServicePerformance(writer, report.services());
            }

            if (report.products() != null && !report.products().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Product Performance"});
                writeProductPerformance(writer, report.products());
            }
        }
        return sw.toString();
    }

    public static String generateRevenueReportCsv(RevenueReportDTO report) throws IOException {
        StringWriter sw = new StringWriter();
        try (CSVWriter writer = new CSVWriter(sw)) {
            writeHeaders(writer, "Actual Revenue Report - " + report.dateFrom().format(DATE_FORMATTER) +
                    " to " + report.dateTo().format(DATE_FORMATTER));
            writeRevenueSummary(writer, report.summary());

            if (report.byPaymentMethod() != null && !report.byPaymentMethod().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Collected by Payment Method"});
                writer.writeNext(new String[]{"Method", "Amount"});
                for (RevenueByPaymentMethodDTO m : report.byPaymentMethod()) {
                    writer.writeNext(new String[]{m.label(), formatCurrency(m.amount())});
                }
            }

            if (report.byTherapist() != null && !report.byTherapist().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Revenue by Therapist"});
                writer.writeNext(new String[]{"Therapist", "Gross Revenue", "Discount", "Net Revenue"});
                for (RevenueByTherapistDTO t : report.byTherapist()) {
                    writer.writeNext(new String[]{
                            t.therapistName(), formatCurrency(t.grossRevenue()),
                            formatCurrency(t.discountAmount()), formatCurrency(t.netRevenue())
                    });
                }
            }

            if (report.servicesNetRevenue() != null && !report.servicesNetRevenue().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Net Revenue by Service"});
                writeCatalogItemRevenue(writer, report.servicesNetRevenue());
            }

            if (report.productsNetRevenue() != null && !report.productsNetRevenue().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Net Revenue by Product"});
                writeCatalogItemRevenue(writer, report.productsNetRevenue());
            }

            if (report.appointments() != null && !report.appointments().getContent().isEmpty()) {
                writer.writeNext(new String[]{});
                writer.writeNext(new String[]{"Appointments"});
                writer.writeNext(new String[]{
                        "Date/Time", "Patient", "Therapist", "Status", "Gross", "Discount",
                        "Net", "Collected", "Outstanding", "Payment Method"
                });
                for (AppointmentRevenueRowDTO row : report.appointments()) {
                    writer.writeNext(new String[]{
                            row.dateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            row.patientName(), row.therapistName(), row.status().name(),
                            formatCurrency(row.gross()), formatCurrency(row.discount()), formatCurrency(row.net()),
                            formatCurrency(row.collected()), formatCurrency(row.outstanding()),
                            row.paymentMethod() != null ? row.paymentMethod().name() : "N/A"
                    });
                }
            }
        }
        return sw.toString();
    }

    private static void writeRevenueSummary(CSVWriter writer, RevenueSummaryDTO summary) throws IOException {
        writer.writeNext(new String[]{"Summary"});
        writer.writeNext(new String[]{"Appointments", String.valueOf(summary.appointmentCount())});
        writer.writeNext(new String[]{"Gross Revenue", formatCurrency(summary.grossRevenue())});
        writer.writeNext(new String[]{"Combo Discounts Given", formatCurrency(summary.comboDiscount())});
        writer.writeNext(new String[]{"Manual Discounts Given", formatCurrency(summary.manualDiscount())});
        writer.writeNext(new String[]{"Net Revenue (Billed)", formatCurrency(summary.netRevenue())});
        writer.writeNext(new String[]{"Collected", formatCurrency(summary.collected())});
        writer.writeNext(new String[]{"Outstanding", formatCurrency(summary.outstanding())});
        writer.writeNext(new String[]{"Wallet-Funded Portion", formatCurrency(summary.walletFunded())});
        writer.writeNext(new String[]{"Advance Payments (Scheduled/Cancelled/No-Show)", formatCurrency(summary.advanceReceived())});
        writer.writeNext(new String[]{});
    }

    private static void writeCatalogItemRevenue(CSVWriter writer, List<RevenueByCatalogItemDTO> items) throws IOException {
        writer.writeNext(new String[]{"Name", "Tags", "Bookings", "Net Revenue"});
        for (RevenueByCatalogItemDTO item : items) {
            writer.writeNext(new String[]{
                    item.name(), String.join(", ", item.tags()),
                    String.valueOf(item.bookingsCount()), formatCurrency(item.netRevenue())
            });
        }
    }

    private static void writeHeaders(CSVWriter writer, String title) {
        writer.writeNext(new String[]{title});
        writer.writeNext(new String[]{"Generated: " + LocalDate.now().format(DATE_FORMATTER)});
        writer.writeNext(new String[]{});
    }

    private static void writePeriodSummary(CSVWriter writer, PeriodSummaryDTO summary) throws IOException {
        writer.writeNext(new String[]{"Summary"});
        writer.writeNext(new String[]{
                "Total Appointments", String.valueOf(summary.totalAppointments()),
                "Unique Patients", String.valueOf(summary.uniquePatients())
        });
        writer.writeNext(new String[]{
                "Services Revenue (Pre-Discount)", formatCurrency(summary.totalServicesRevenue()),
                "Products Revenue (Pre-Discount)", formatCurrency(summary.totalProductsRevenue()),
                "Total Revenue (Pre-Discount)", formatCurrency(summary.totalRevenue())
        });
        writer.writeNext(new String[]{});
    }

    private static void writeTherapistEarnings(CSVWriter writer, List<TherapistEarningsDTO> earnings) throws IOException {
        writer.writeNext(new String[]{
                "Therapist", "Services Rev.(All, Pre-Discount)", "Products Rev.(All, Pre-Discount)", "Services(All)",
                "Services Rev.(Bonus tagged, Pre-Discount)", "Products Rev.(Commission tagged, Pre-Discount)", "Services(Bonus tagged)",
                "Service Commission", "Product Commission", "Total Commission",
                "Bonus Earned", "Bonus Amount", "Total Variable Pay", "Fixed Salary"
        });
        for (TherapistEarningsDTO earning : earnings) {
            writer.writeNext(new String[]{
                    earning.therapist().getFullName(),
                    formatCurrency(earning.allServicesRevenue()),
                    formatCurrency(earning.allProductsRevenue()),
                    String.valueOf(earning.allServicesCount()),
                    formatCurrency(earning.bonusTaggedServicesRevenue()),
                    formatCurrency(earning.productsRevenue()),
                    String.valueOf(earning.servicesCount()),
                    formatCurrency(earning.serviceCommission()),
                    formatCurrency(earning.productCommission()),
                    formatCurrency(earning.totalCommission()),
                    earning.bonusEarned() ? "Yes" : "No",
                    formatCurrency(earning.bonusAmount()),
                    formatCurrency(earning.totalVariablePay()),
                    formatCurrency(earning.fixedMonthlySalary())
            });
        }
    }

    private static void writeTagRevenue(CSVWriter writer, List<TagRevenueDTO> tagRevenues) throws IOException {
        writer.writeNext(new String[]{"Tag", "Revenue (Pre-Discount)"});
        for (TagRevenueDTO tag : tagRevenues) {
            writer.writeNext(new String[]{
                    tag.tagName(),
                    formatCurrency(tag.revenue())
            });
        }
    }

    private static void writeProductPerformance(CSVWriter writer, List<ProductPerformanceDTO> products) throws IOException {
        writer.writeNext(new String[]{"Product Name", "Units Sold", "Revenue (Pre-Discount)", "Stock Level"});
        for (ProductPerformanceDTO product : products) {
            writer.writeNext(new String[]{
                    product.productName(),
                    String.valueOf(product.unitsSold()),
                    formatCurrency(product.revenue()),
                    String.valueOf(product.stockQuantity())
            });
        }
    }

    private static void writeServicePerformance(CSVWriter writer, List<ServicePerformanceDTO> services) throws IOException {
        writer.writeNext(new String[]{"Service Name", "Count", "Revenue (Pre-Discount)", "Average Price (Pre-Discount)", "Top Therapist"});
        for (ServicePerformanceDTO service : services) {
            writer.writeNext(new String[]{
                    service.serviceName(),
                    String.valueOf(service.bookingsCount()),
                    formatCurrency(service.revenue()),
                    formatCurrency(service.averagePrice()),
                    service.topTherapistName() != null ? service.topTherapistName() : "N/A"
            });
        }
    }

    private static void writeTherapistPatientMetrics(CSVWriter writer, List<TherapistPatientMetricsDTO> metrics) throws IOException {
        writer.writeNext(new String[]{"Therapist", "New Patients", "Repeat Patients", "Retention Rate"});
        for (TherapistPatientMetricsDTO metric : metrics) {
            writer.writeNext(new String[]{
                    metric.therapist().getFullName(),
                    String.valueOf(metric.newPatients()),
                    String.valueOf(metric.repeatPatients()),
                    formatPercentage(metric.retentionRate())
            });
        }
    }

    private static String formatCurrency(BigDecimal value) {
        if (value == null) return "₹0.00";
        return "₹" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** retentionRate() is already 0-100 scaled — no extra *100 here. */
    private static String formatPercentage(Double value) {
        if (value == null) return "0%";
        return String.format("%.2f%%", value);
    }
}
