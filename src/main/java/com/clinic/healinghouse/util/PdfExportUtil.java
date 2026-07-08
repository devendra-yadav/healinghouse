package com.clinic.healinghouse.util;

import com.clinic.healinghouse.dto.*;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PdfExportUtil {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static byte[] generateDailyReportPdf(DailyReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        addHeader(document, "Daily Report - " + report.date().format(DATE_FORMATTER));
        addPeriodSummaryTable(document, report.summary());
        addTherapistEarningsTable(document, report.therapistEarnings());
        addFooter(document);

        document.close();
        return baos.toByteArray();
    }

    public static byte[] generatePeriodReportPdf(PeriodReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        addHeader(document, "Period Report - " + report.dateFrom().format(DATE_FORMATTER) +
                " to " + report.dateTo().format(DATE_FORMATTER));
        addPeriodSummaryTable(document, report.summary());
        addTherapistEarningsTable(document, report.therapistEarnings());

        if (report.tagRevenue() != null && !report.tagRevenue().isEmpty()) {
            addSectionTitle(document, "Tag Revenue Breakdown");
            addTagRevenueTable(document, report.tagRevenue());
        }

        if (report.productPerformance() != null && !report.productPerformance().isEmpty()) {
            addSectionTitle(document, "Product Performance");
            addProductPerformanceTable(document, report.productPerformance());
        }

        addFooter(document);
        document.close();
        return baos.toByteArray();
    }

    public static byte[] generateComparisonReportPdf(ComparisonReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        addHeader(document, "Therapist Comparison - " + report.dateFrom().format(DATE_FORMATTER) +
                " to " + report.dateTo().format(DATE_FORMATTER));
        addTherapistEarningsTable(document, report.therapistEarnings());
        addFooter(document);

        document.close();
        return baos.toByteArray();
    }

    public static byte[] generatePatientReportPdf(PatientReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        addHeader(document, "Patient Acquisition Report - " + report.dateFrom().format(DATE_FORMATTER) +
                " to " + report.dateTo().format(DATE_FORMATTER));

        Table table = new Table(new float[]{2, 1, 2, 1, 2, 1});
        table.setWidth(UnitValue.createPercentValue(100));
        addHeaderCell(table, "New Patients");
        addDataCell(table, String.valueOf(report.totalNewPatients()));
        addHeaderCell(table, "Repeat Patients");
        addDataCell(table, String.valueOf(report.totalRepeatPatients()));
        addHeaderCell(table, "Overall Retention");
        addDataCell(table, formatPercentage(report.overallRetentionRate()));
        document.add(table);

        if (report.therapistMetrics() != null && !report.therapistMetrics().isEmpty()) {
            addSectionTitle(document, "Therapist Patient Metrics");
            addTherapistPatientMetricsTable(document, report.therapistMetrics());
        }

        addFooter(document);
        document.close();
        return baos.toByteArray();
    }

    public static byte[] generatePerformanceReportPdf(PerformanceReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        addHeader(document, "Product/Service Performance - " + report.dateFrom().format(DATE_FORMATTER) +
                " to " + report.dateTo().format(DATE_FORMATTER));

        if (report.services() != null && !report.services().isEmpty()) {
            addSectionTitle(document, "Service Performance");
            addServicePerformanceTable(document, report.services());
        }

        if (report.products() != null && !report.products().isEmpty()) {
            addSectionTitle(document, "Product Performance");
            addProductPerformanceTable(document, report.products());
        }

        addFooter(document);
        document.close();
        return baos.toByteArray();
    }

    private static void addHeader(Document document, String title) {
        document.add(new Paragraph("Healing House Clinic")
                .setBold().setFontSize(18).setTextAlignment(TextAlignment.CENTER));
        document.add(new Paragraph(title)
                .setFontSize(14).setTextAlignment(TextAlignment.CENTER));
        document.add(new Paragraph("Generated: " + LocalDate.now().format(DATE_FORMATTER))
                .setFontSize(10).setTextAlignment(TextAlignment.RIGHT));
        document.add(new Paragraph("\n"));
    }

    private static void addSectionTitle(Document document, String title) {
        document.add(new Paragraph("\n"));
        document.add(new Paragraph(title).setBold().setFontSize(12));
    }

    private static void addFooter(Document document) {
        document.add(new Paragraph("\n"));
        document.add(new Paragraph("This is an official clinic report. Please keep confidential.")
                .setFontSize(8).setTextAlignment(TextAlignment.CENTER));
    }

    private static void addPeriodSummaryTable(Document document, PeriodSummaryDTO summary) {
        Table table = new Table(new float[]{2, 1, 2, 1, 2, 1});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Total Appointments");
        addDataCell(table, String.valueOf(summary.totalAppointments()));
        addHeaderCell(table, "Unique Patients");
        addDataCell(table, String.valueOf(summary.uniquePatients()));
        addHeaderCell(table, "Total Revenue");
        addDataCell(table, formatCurrency(summary.totalRevenue()));

        addHeaderCell(table, "Services Revenue");
        addDataCell(table, formatCurrency(summary.totalServicesRevenue()));
        addHeaderCell(table, "Products Revenue");
        addDataCell(table, formatCurrency(summary.totalProductsRevenue()));
        addHeaderCell(table, "");
        addDataCell(table, "");

        document.add(table);
    }

    private static void addTherapistEarningsTable(Document document, List<TherapistEarningsDTO> earnings) {
        Table table = new Table(new float[]{1.5f, 1, 1, 0.8f, 1, 1, 1, 0.8f, 1, 1, 1});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Therapist");
        addHeaderCell(table, "Svcs Revenue");
        addHeaderCell(table, "Prod Revenue");
        addHeaderCell(table, "Cnt");
        addHeaderCell(table, "Svc Comm");
        addHeaderCell(table, "Prod Comm");
        addHeaderCell(table, "Total Comm");
        addHeaderCell(table, "Bonus");
        addHeaderCell(table, "Bonus Amt");
        addHeaderCell(table, "Variable Pay");
        addHeaderCell(table, "Fixed Salary");

        for (TherapistEarningsDTO earning : earnings) {
            addDataCell(table, earning.therapist().getFullName());
            addDataCell(table, formatCurrency(earning.servicesRevenue()));
            addDataCell(table, formatCurrency(earning.productsRevenue()));
            addDataCell(table, String.valueOf(earning.servicesCount()));
            addDataCell(table, formatCurrency(earning.serviceCommission()));
            addDataCell(table, formatCurrency(earning.productCommission()));
            addDataCell(table, formatCurrency(earning.totalCommission()));
            addDataCell(table, earning.bonusEarned() ? "Yes" : "No");
            addDataCell(table, formatCurrency(earning.bonusAmount()));
            addDataCell(table, formatCurrency(earning.totalVariablePay()));
            addDataCell(table, formatCurrency(earning.fixedMonthlySalary()));
        }

        document.add(table);
    }

    private static void addTagRevenueTable(Document document, List<TagRevenueDTO> tagRevenues) {
        Table table = new Table(new float[]{2, 1});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Tag");
        addHeaderCell(table, "Revenue");

        for (TagRevenueDTO tag : tagRevenues) {
            addDataCell(table, tag.tagName());
            addDataCell(table, formatCurrency(tag.revenue()));
        }

        document.add(table);
    }

    private static void addProductPerformanceTable(Document document, List<ProductPerformanceDTO> products) {
        Table table = new Table(new float[]{2, 1, 1, 1});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Product Name");
        addHeaderCell(table, "Units Sold");
        addHeaderCell(table, "Revenue");
        addHeaderCell(table, "Stock Level");

        for (ProductPerformanceDTO product : products) {
            addDataCell(table, product.productName());
            addDataCell(table, String.valueOf(product.unitsSold()));
            addDataCell(table, formatCurrency(product.revenue()));
            addDataCell(table, String.valueOf(product.stockQuantity()));
        }

        document.add(table);
    }

    private static void addServicePerformanceTable(Document document, List<ServicePerformanceDTO> services) {
        Table table = new Table(new float[]{2, 0.8f, 1, 1, 1.5f});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Service Name");
        addHeaderCell(table, "Count");
        addHeaderCell(table, "Revenue");
        addHeaderCell(table, "Avg Price");
        addHeaderCell(table, "Top Therapist");

        for (ServicePerformanceDTO service : services) {
            addDataCell(table, service.serviceName());
            addDataCell(table, String.valueOf(service.bookingsCount()));
            addDataCell(table, formatCurrency(service.revenue()));
            addDataCell(table, formatCurrency(service.averagePrice()));
            addDataCell(table, service.topTherapistName() != null ? service.topTherapistName() : "N/A");
        }

        document.add(table);
    }

    private static void addTherapistPatientMetricsTable(Document document, List<TherapistPatientMetricsDTO> metrics) {
        Table table = new Table(new float[]{1.5f, 1, 1, 1});
        table.setWidth(UnitValue.createPercentValue(100));

        addHeaderCell(table, "Therapist");
        addHeaderCell(table, "New Patients");
        addHeaderCell(table, "Repeat Patients");
        addHeaderCell(table, "Retention Rate");

        for (TherapistPatientMetricsDTO metric : metrics) {
            addDataCell(table, metric.therapist().getFullName());
            addDataCell(table, String.valueOf(metric.newPatients()));
            addDataCell(table, String.valueOf(metric.repeatPatients()));
            addDataCell(table, formatPercentage(metric.retentionRate()));
        }

        document.add(table);
    }

    private static void addHeaderCell(Table table, String content) {
        Cell cell = new Cell().add(new Paragraph(content).setBold());
        cell.setTextAlignment(TextAlignment.CENTER);
        table.addCell(cell);
    }

    private static void addDataCell(Table table, String content) {
        Cell cell = new Cell().add(new Paragraph(content));
        cell.setTextAlignment(TextAlignment.LEFT);
        table.addCell(cell);
    }

    private static String formatCurrency(BigDecimal value) {
        if (value == null) return "₹0.00";
        return "₹" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatPercentage(Double value) {
        if (value == null) return "0%";
        return String.format("%.2f%%", value * 100);
    }
}
