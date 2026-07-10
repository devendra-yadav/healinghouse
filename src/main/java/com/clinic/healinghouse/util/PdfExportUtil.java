package com.clinic.healinghouse.util;

import com.clinic.healinghouse.dto.*;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PdfExportUtil {

    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter GENERATED_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,##0.00");

    private static final Color BRAND_PRIMARY = new DeviceRgb(0xAE, 0x2E, 0x2B);
    private static final Color BRAND_DARK = new DeviceRgb(0x6F, 0x20, 0x1C);
    private static final Color TEXT_DARK = new DeviceRgb(0x33, 0x33, 0x33);
    private static final Color TEXT_MUTED = new DeviceRgb(0x88, 0x88, 0x88);
    private static final Color ROW_SHADE = new DeviceRgb(0xF8, 0xEF, 0xEE);
    private static final Color BORDER_COLOR = new DeviceRgb(0xDD, 0xDD, 0xDD);
    private static final Color POSITIVE = new DeviceRgb(0x2E, 0x7D, 0x32);

    private static final float MARGIN_TOP = 30f;
    private static final float MARGIN_SIDE = 28f;
    private static final float MARGIN_BOTTOM = 55f;

    // FontProgram (raw glyph data) is safe to share across documents; a PdfFont built from it is not —
    // it gets bound to whichever PdfDocument first flushes it, so each generate*Pdf call needs its own instance.
    private static final FontProgram FONT_PROGRAM_REGULAR;
    private static final FontProgram FONT_PROGRAM_BOLD;
    private static final byte[] LOGO_BYTES;

    private static final ThreadLocal<PdfFont> CURRENT_REGULAR_FONT = new ThreadLocal<>();
    private static final ThreadLocal<PdfFont> CURRENT_BOLD_FONT = new ThreadLocal<>();

    static {
        try {
            FONT_PROGRAM_REGULAR = FontProgramFactory.createFont(readClasspathResource("/fonts/DejaVuSans.ttf"));
            FONT_PROGRAM_BOLD = FontProgramFactory.createFont(readClasspathResource("/fonts/DejaVuSans-Bold.ttf"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        LOGO_BYTES = readClasspathResourceQuietly("/static/images/clinic_logo.jpeg");
    }

    private static PdfFont regularFont() {
        return CURRENT_REGULAR_FONT.get();
    }

    private static PdfFont boldFont() {
        return CURRENT_BOLD_FONT.get();
    }

    private static void initFontsForDocument() throws IOException {
        CURRENT_REGULAR_FONT.set(PdfFontFactory.createFont(FONT_PROGRAM_REGULAR,
                PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED));
        CURRENT_BOLD_FONT.set(PdfFontFactory.createFont(FONT_PROGRAM_BOLD,
                PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED));
    }

    private static byte[] readClasspathResource(String path) throws Exception {
        try (InputStream in = PdfExportUtil.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] readClasspathResourceQuietly(String path) {
        try {
            return readClasspathResource(path);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] generateDailyReportPdf(DailyReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, true);

        addLetterhead(document, "Daily Report", "For " + report.date().format(DISPLAY_DATE_FORMATTER));
        addPeriodSummaryTable(document, report.summary());
        addSectionTitle(document, "Therapist Earnings");
        addTherapistEarningsTable(document, report.therapistEarnings());

        finish(document, pdfDoc);
        return baos.toByteArray();
    }

    public static byte[] generatePeriodReportPdf(PeriodReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, true);

        addLetterhead(document, "Period Report", "From " + report.dateFrom().format(DISPLAY_DATE_FORMATTER) +
                "  to  " + report.dateTo().format(DISPLAY_DATE_FORMATTER));
        addPeriodSummaryTable(document, report.summary());
        addSectionTitle(document, "Therapist Earnings");
        addTherapistEarningsTable(document, report.therapistEarnings());

        if (report.tagRevenue() != null && !report.tagRevenue().isEmpty()) {
            addSection(document, "Tag Revenue Breakdown", buildTagRevenueTable(report.tagRevenue()));
        }

        if (report.productPerformance() != null && !report.productPerformance().isEmpty()) {
            addSection(document, "Product Performance", buildProductPerformanceTable(report.productPerformance()));
        }

        finish(document, pdfDoc);
        return baos.toByteArray();
    }

    public static byte[] generateComparisonReportPdf(ComparisonReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, true);

        addLetterhead(document, "Therapist Comparison", "From " + report.dateFrom().format(DISPLAY_DATE_FORMATTER) +
                "  to  " + report.dateTo().format(DISPLAY_DATE_FORMATTER));
        addTherapistEarningsTable(document, report.therapistEarnings());

        finish(document, pdfDoc);
        return baos.toByteArray();
    }

    public static byte[] generatePatientReportPdf(PatientReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, false);

        addLetterhead(document, "Patient Acquisition Report", "From " + report.dateFrom().format(DISPLAY_DATE_FORMATTER) +
                "  to  " + report.dateTo().format(DISPLAY_DATE_FORMATTER));

        Table table = newTable(new float[]{2, 1, 2, 1, 2, 1}, 9f);
        addLabelCell(table, "New Patients", TextAlignment.CENTER);
        addDataCell(table, String.valueOf(report.totalNewPatients()), TextAlignment.CENTER, false);
        addLabelCell(table, "Repeat Patients", TextAlignment.CENTER);
        addDataCell(table, String.valueOf(report.totalRepeatPatients()), TextAlignment.CENTER, false);
        addLabelCell(table, "Overall Retention", TextAlignment.CENTER);
        addDataCell(table, formatPercentage(report.overallRetentionRate()), TextAlignment.CENTER, false);
        document.add(table);

        if (report.therapistMetrics() != null && !report.therapistMetrics().isEmpty()) {
            addSection(document, "Therapist Patient Metrics", buildTherapistPatientMetricsTable(report.therapistMetrics()));
        }

        finish(document, pdfDoc);
        return baos.toByteArray();
    }

    public static byte[] generatePerformanceReportPdf(PerformanceReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, false);

        addLetterhead(document, "Product/Service Performance", "From " + report.dateFrom().format(DISPLAY_DATE_FORMATTER) +
                "  to  " + report.dateTo().format(DISPLAY_DATE_FORMATTER));

        if (report.services() != null && !report.services().isEmpty()) {
            addSection(document, "Service Performance", buildServicePerformanceTable(report.services()));
        }

        if (report.products() != null && !report.products().isEmpty()) {
            addSection(document, "Product Performance", buildProductPerformanceTable(report.products()));
        }

        finish(document, pdfDoc);
        return baos.toByteArray();
    }

    // ---- document scaffolding ----------------------------------------------------------

    private static Document newDocument(PdfDocument pdfDoc, boolean landscape) throws IOException {
        initFontsForDocument();
        PageSize pageSize = landscape ? PageSize.A4.rotate() : PageSize.A4;
        Document document = new Document(pdfDoc, pageSize);
        document.setMargins(MARGIN_TOP, MARGIN_SIDE, MARGIN_BOTTOM, MARGIN_SIDE);
        document.setFont(regularFont());
        document.setFontSize(9.5f);
        document.setFontColor(TEXT_DARK);
        return document;
    }

    private static void finish(Document document, PdfDocument pdfDoc) {
        stampFooters(pdfDoc);
        document.close();
        CURRENT_REGULAR_FONT.remove();
        CURRENT_BOLD_FONT.remove();
    }

    private static void addLetterhead(Document document, String title, String subtitle) {
        Table header = new Table(UnitValue.createPercentArray(new float[]{1, 5}));
        header.setWidth(UnitValue.createPercentValue(100));
        header.setBorder(Border.NO_BORDER);

        Cell logoCell = new Cell().setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(0);
        if (LOGO_BYTES != null) {
            Image logo = new Image(ImageDataFactory.create(LOGO_BYTES));
            logo.setWidth(48).setHeight(48);
            logoCell.add(logo);
        }
        header.addCell(logoCell);

        Cell textCell = new Cell().setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(0).setPaddingLeft(10);
        textCell.add(new Paragraph("Healing House Clinic")
                .setFont(boldFont()).setFontSize(19).setFontColor(BRAND_PRIMARY).setMultipliedLeading(1f).setMargin(0));
        textCell.add(new Paragraph(title)
                .setFont(boldFont()).setFontSize(12.5f).setFontColor(TEXT_DARK).setMultipliedLeading(1.2f).setMargin(0));
        textCell.add(new Paragraph(subtitle)
                .setFontSize(9.5f).setFontColor(TEXT_MUTED).setMultipliedLeading(1.2f).setMargin(0));
        header.addCell(textCell);

        document.add(header);

        document.add(new LineSeparator(new SolidLine(1.25f)).setStrokeColor(BRAND_PRIMARY).setMarginTop(8).setMarginBottom(4));
        document.add(new Paragraph("Generated on " + LocalDateTime.now().format(GENERATED_FORMATTER))
                .setFontSize(7.5f).setFontColor(TEXT_MUTED).setTextAlignment(TextAlignment.RIGHT).setMargin(0));
        document.add(new Paragraph("\n").setFontSize(4));
    }

    private static void addSectionTitle(Document document, String title) {
        document.add(sectionTitleParagraph(title));
        document.add(sectionRuleLine());
    }

    /**
     * Adds a titled table as a single atomic block. Tables normally split across a page break
     * on their own (desirable for long tables — the header row repeats), but a plain heading
     * added just before one doesn't share in that: if the table itself doesn't fit, only it
     * moves to the next page, leaving the heading orphaned at the bottom of the previous one.
     * Wrapping both in a keep-together Div moves the whole section together instead.
     */
    private static void addSection(Document document, String title, Table table) {
        Div section = new Div().setKeepTogether(true);
        section.add(sectionTitleParagraph(title));
        section.add(sectionRuleLine());
        section.add(table);
        document.add(section);
    }

    private static Paragraph sectionTitleParagraph(String title) {
        return new Paragraph(title)
                .setFont(boldFont()).setFontSize(11.5f).setFontColor(BRAND_DARK)
                .setMarginTop(12).setMarginBottom(2);
    }

    private static LineSeparator sectionRuleLine() {
        return new LineSeparator(new SolidLine(0.75f)).setStrokeColor(BORDER_COLOR).setMarginBottom(6);
    }

    private static void stampFooters(PdfDocument pdfDoc) {
        int totalPages = pdfDoc.getNumberOfPages();
        for (int i = 1; i <= totalPages; i++) {
            PdfPage page = pdfDoc.getPage(i);
            Rectangle pageSize = page.getPageSize();
            float footerY = pageSize.getBottom() + 26;

            PdfCanvas pdfCanvas = new PdfCanvas(page);
            pdfCanvas.saveState()
                    .setStrokeColor(BORDER_COLOR)
                    .setLineWidth(0.75f)
                    .moveTo(pageSize.getLeft() + MARGIN_SIDE, footerY + 12)
                    .lineTo(pageSize.getRight() - MARGIN_SIDE, footerY + 12)
                    .stroke()
                    .restoreState();

            try (Canvas canvas = new Canvas(pdfCanvas, pageSize)) {
                canvas.setFont(regularFont()).setFontSize(8).setFontColor(TEXT_MUTED);
                canvas.showTextAligned("Healing House Clinic — Confidential, for internal use only",
                        pageSize.getLeft() + MARGIN_SIDE, footerY, TextAlignment.LEFT);
                canvas.showTextAligned("Page " + i + " of " + totalPages,
                        pageSize.getRight() - MARGIN_SIDE, footerY, TextAlignment.RIGHT);
            }
        }
    }

    // ---- tables ----------------------------------------------------------------------

    private static Table newTable(float[] widths, float fontSize) {
        Table table = new Table(UnitValue.createPercentArray(widths));
        table.setWidth(UnitValue.createPercentValue(100));
        table.setFontSize(fontSize);
        table.setBorder(new SolidBorder(BORDER_COLOR, 0.75f));
        table.setMarginBottom(10);
        return table;
    }

    private static void addPeriodSummaryTable(Document document, PeriodSummaryDTO summary) {
        Table table = newTable(new float[]{2, 1, 2, 1, 2, 1}, 9.5f);

        addLabelCell(table, "Total Appointments", TextAlignment.LEFT);
        addDataCell(table, String.valueOf(summary.totalAppointments()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Unique Patients", TextAlignment.LEFT);
        addDataCell(table, String.valueOf(summary.uniquePatients()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Total Revenue", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalRevenue()), TextAlignment.RIGHT, false);

        addLabelCell(table, "Services Revenue", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalServicesRevenue()), TextAlignment.RIGHT, true);
        addLabelCell(table, "Products Revenue", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalProductsRevenue()), TextAlignment.RIGHT, true);
        addBlankCell(table);
        addBlankCell(table);

        document.add(table);
    }

    private static void addTherapistEarningsTable(Document document, List<TherapistEarningsDTO> earnings) {
        Table table = newTable(new float[]{1.6f, 1, 1, 0.7f, 1, 1.1f, 0.7f, 1, 1, 1, 0.7f, 1, 1, 1}, 7.5f);

        addHeaderCell(table, "Therapist", TextAlignment.LEFT);
        addHeaderCell(table, "Svcs Rev.(All)", TextAlignment.RIGHT);
        addHeaderCell(table, "Prod Rev.(All)", TextAlignment.RIGHT);
        addHeaderCell(table, "Svcs(All)", TextAlignment.CENTER);
        addHeaderCell(table, "Svcs Rev.(Bonus)", TextAlignment.RIGHT);
        addHeaderCell(table, "Prod Rev.(Comm.)", TextAlignment.RIGHT);
        addHeaderCell(table, "Svcs(Bonus)", TextAlignment.CENTER);
        addHeaderCell(table, "Svc Comm", TextAlignment.RIGHT);
        addHeaderCell(table, "Prod Comm", TextAlignment.RIGHT);
        addHeaderCell(table, "Total Comm", TextAlignment.RIGHT);
        addHeaderCell(table, "Bonus", TextAlignment.CENTER);
        addHeaderCell(table, "Bonus Amt", TextAlignment.RIGHT);
        addHeaderCell(table, "Variable Pay", TextAlignment.RIGHT);
        addHeaderCell(table, "Fixed Salary", TextAlignment.RIGHT);

        boolean shaded = false;
        for (TherapistEarningsDTO earning : earnings) {
            addDataCell(table, earning.therapist().getFullName(), TextAlignment.LEFT, shaded);
            addDataCell(table, formatCurrency(earning.allServicesRevenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.allProductsRevenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, String.valueOf(earning.allServicesCount()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(earning.bonusTaggedServicesRevenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.productsRevenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, String.valueOf(earning.servicesCount()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(earning.serviceCommission()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.productCommission()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.totalCommission()), TextAlignment.RIGHT, shaded);
            addBadgeCell(table, earning.bonusEarned() ? "Yes" : "No", earning.bonusEarned(), shaded);
            addDataCell(table, formatCurrency(earning.bonusAmount()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.totalVariablePay()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(earning.fixedMonthlySalary()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }

        document.add(table);
    }

    private static Table buildTagRevenueTable(List<TagRevenueDTO> tagRevenues) {
        Table table = newTable(new float[]{2, 1}, 9.5f);

        addHeaderCell(table, "Tag", TextAlignment.LEFT);
        addHeaderCell(table, "Revenue", TextAlignment.RIGHT);

        boolean shaded = false;
        for (TagRevenueDTO tag : tagRevenues) {
            addDataCell(table, tag.tagName(), TextAlignment.LEFT, shaded);
            addDataCell(table, formatCurrency(tag.revenue()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }

        return table;
    }

    private static Table buildProductPerformanceTable(List<ProductPerformanceDTO> products) {
        Table table = newTable(new float[]{2, 1, 1, 1}, 9.5f);

        addHeaderCell(table, "Product Name", TextAlignment.LEFT);
        addHeaderCell(table, "Units Sold", TextAlignment.CENTER);
        addHeaderCell(table, "Revenue", TextAlignment.RIGHT);
        addHeaderCell(table, "Stock Level", TextAlignment.CENTER);

        boolean shaded = false;
        for (ProductPerformanceDTO product : products) {
            addDataCell(table, product.productName(), TextAlignment.LEFT, shaded);
            addDataCell(table, String.valueOf(product.unitsSold()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(product.revenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, String.valueOf(product.stockQuantity()), TextAlignment.CENTER, shaded);
            shaded = !shaded;
        }

        return table;
    }

    private static Table buildServicePerformanceTable(List<ServicePerformanceDTO> services) {
        Table table = newTable(new float[]{2, 0.8f, 1, 1, 1.5f}, 9.5f);

        addHeaderCell(table, "Service Name", TextAlignment.LEFT);
        addHeaderCell(table, "Count", TextAlignment.CENTER);
        addHeaderCell(table, "Revenue", TextAlignment.RIGHT);
        addHeaderCell(table, "Avg Price", TextAlignment.RIGHT);
        addHeaderCell(table, "Top Therapist", TextAlignment.LEFT);

        boolean shaded = false;
        for (ServicePerformanceDTO service : services) {
            addDataCell(table, service.serviceName(), TextAlignment.LEFT, shaded);
            addDataCell(table, String.valueOf(service.bookingsCount()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(service.revenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(service.averagePrice()), TextAlignment.RIGHT, shaded);
            addDataCell(table, service.topTherapistName() != null ? service.topTherapistName() : "N/A", TextAlignment.LEFT, shaded);
            shaded = !shaded;
        }

        return table;
    }

    private static Table buildTherapistPatientMetricsTable(List<TherapistPatientMetricsDTO> metrics) {
        Table table = newTable(new float[]{1.5f, 1, 1, 1}, 9.5f);

        addHeaderCell(table, "Therapist", TextAlignment.LEFT);
        addHeaderCell(table, "New Patients", TextAlignment.CENTER);
        addHeaderCell(table, "Repeat Patients", TextAlignment.CENTER);
        addHeaderCell(table, "Retention Rate", TextAlignment.RIGHT);

        boolean shaded = false;
        for (TherapistPatientMetricsDTO metric : metrics) {
            addDataCell(table, metric.therapist().getFullName(), TextAlignment.LEFT, shaded);
            addDataCell(table, String.valueOf(metric.newPatients()), TextAlignment.CENTER, shaded);
            addDataCell(table, String.valueOf(metric.repeatPatients()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatPercentage(metric.retentionRate()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }

        return table;
    }

    // ---- cell styling ------------------------------------------------------------------

    private static void addHeaderCell(Table table, String content, TextAlignment align) {
        table.addHeaderCell(labelStyledCell(content, align));
    }

    /** Styled like a header cell, but added as a normal body cell — for label:value summary
     * grids where labels and values are interleaved within a row rather than forming a
     * standalone header row (using table.addHeaderCell there would misregister a repeating
     * page header and corrupt the row layout). */
    private static void addLabelCell(Table table, String content, TextAlignment align) {
        table.addCell(labelStyledCell(content, align));
    }

    private static Cell labelStyledCell(String content, TextAlignment align) {
        Cell cell = new Cell().add(new Paragraph(content).setFont(boldFont()).setFontColor(ColorConstants.WHITE));
        cell.setBackgroundColor(BRAND_PRIMARY);
        cell.setTextAlignment(align);
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
        cell.setBorder(Border.NO_BORDER);
        cell.setPadding(5);
        return cell;
    }

    private static void addBlankCell(Table table) {
        Cell cell = new Cell().setBorder(Border.NO_BORDER);
        table.addCell(cell);
    }

    private static void addDataCell(Table table, String content, TextAlignment align, boolean shaded) {
        Cell cell = new Cell().add(new Paragraph(content == null ? "" : content));
        cell.setTextAlignment(align);
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
        cell.setBorder(Border.NO_BORDER);
        cell.setBorderBottom(new SolidBorder(BORDER_COLOR, 0.5f));
        cell.setBackgroundColor(shaded ? ROW_SHADE : ColorConstants.WHITE);
        cell.setPadding(4.5f);
        table.addCell(cell);
    }

    private static void addBadgeCell(Table table, String content, boolean positive, boolean shaded) {
        Cell cell = new Cell().add(new Paragraph(content).setFont(boldFont())
                .setFontColor(positive ? POSITIVE : TEXT_MUTED));
        cell.setTextAlignment(TextAlignment.CENTER);
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
        cell.setBorder(Border.NO_BORDER);
        cell.setBorderBottom(new SolidBorder(BORDER_COLOR, 0.5f));
        cell.setBackgroundColor(shaded ? ROW_SHADE : ColorConstants.WHITE);
        cell.setPadding(4.5f);
        table.addCell(cell);
    }

    // ---- formatting --------------------------------------------------------------------

    private static String formatCurrency(BigDecimal value) {
        if (value == null) return "₹ 0.00";
        return "₹ " + CURRENCY_FORMAT.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatPercentage(Double value) {
        if (value == null) return "0%";
        return String.format("%.2f%%", value * 100);
    }
}
