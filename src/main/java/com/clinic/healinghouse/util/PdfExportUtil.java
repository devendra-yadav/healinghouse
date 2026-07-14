package com.clinic.healinghouse.util;

import com.clinic.healinghouse.dto.*;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
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
    private static final DateTimeFormatter ROW_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM, hh:mm a");
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
    private static final ThreadLocal<FooterEventHandler> CURRENT_FOOTER_HANDLER = new ThreadLocal<>();

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

    public static byte[] generateRevenueReportPdf(RevenueReportDTO report) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
        Document document = newDocument(pdfDoc, true);

        addLetterhead(document, "Actual Revenue Report", "From " + report.dateFrom().format(DISPLAY_DATE_FORMATTER) +
                "  to  " + report.dateTo().format(DISPLAY_DATE_FORMATTER));
        addRevenueSummaryTable(document, report.summary());

        if (report.byPaymentMethod() != null && !report.byPaymentMethod().isEmpty()) {
            addSection(document, "Collected by Payment Method", buildPaymentMethodTable(report.byPaymentMethod()));
        }

        if (report.byTherapist() != null && !report.byTherapist().isEmpty()) {
            addSection(document, "Revenue by Therapist", buildRevenueByTherapistTable(report.byTherapist()));
        }

        if (report.servicesNetRevenue() != null && !report.servicesNetRevenue().isEmpty()) {
            addSection(document, "Net Revenue by Service", buildCatalogItemRevenueTable(report.servicesNetRevenue()));
        }

        if (report.productsNetRevenue() != null && !report.productsNetRevenue().isEmpty()) {
            addSection(document, "Net Revenue by Product", buildCatalogItemRevenueTable(report.productsNetRevenue()));
        }

        if (report.appointments() != null && !report.appointments().getContent().isEmpty()) {
            addSection(document, "Appointments", buildAppointmentRevenueRowsTable(report.appointments().getContent()));
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

        // Footers must be stamped as each page finishes (via an END_PAGE event), not in a
        // post-hoc loop after all content has been added: iText's Document auto-flushes a
        // page's underlying PdfDictionary once a later page is started (a memory-saving
        // measure), so a naive "loop pdfDoc.getPage(1..N)" run after document.add() finds
        // earlier pages already flushed and throws a NullPointerException from
        // PdfPage.getPageSize() — any report spanning 2+ pages hit this on export.
        FooterEventHandler footerHandler = new FooterEventHandler();
        pdfDoc.addEventHandler(PdfDocumentEvent.END_PAGE, footerHandler);
        CURRENT_FOOTER_HANDLER.set(footerHandler);

        return document;
    }

    private static void finish(Document document, PdfDocument pdfDoc) {
        // The "of N" total page count isn't known until every page has been laid out, so each
        // page's footer reserves a blank placeholder XObject during END_PAGE, and this fills in
        // the real count once, right before close — safe because the XObject is its own
        // indirect object and isn't flushed just because the page referencing it already was.
        CURRENT_FOOTER_HANDLER.get().writeTotalPageCount(pdfDoc);
        document.close();
        CURRENT_REGULAR_FONT.remove();
        CURRENT_BOLD_FONT.remove();
        CURRENT_FOOTER_HANDLER.remove();
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
     * Adds a titled table. A plain heading added just before a table doesn't share in the
     * table's own page-break handling (desirable for long tables — the header row repeats):
     * if the table doesn't fit, only it moves to the next page, leaving the heading orphaned
     * at the bottom of the previous one. setKeepWithNext (rather than wrapping the whole
     * section, table included, in a keep-together block) glues just the heading to the start
     * of the table, so only the heading jumps to the next page while the table is still free
     * to split across as many pages as it needs. A keep-together Div was tried here previously,
     * but for tables tall enough that heading+full-table never fits on any single page, iText
     * forces the layout anyway and corrupts the page tree (a later NPE while stamping footers) —
     * see the "Cannot invoke Map.get because this.map is null" bug in PdfPage.getPageSize().
     */
    private static void addSection(Document document, String title, Table table) {
        document.add(sectionTitleParagraph(title));
        document.add(sectionRuleLine());
        document.add(table);
    }

    private static Paragraph sectionTitleParagraph(String title) {
        return new Paragraph(title)
                .setFont(boldFont()).setFontSize(11.5f).setFontColor(BRAND_DARK)
                .setMarginTop(12).setMarginBottom(2)
                .setKeepWithNext(true);
    }

    private static LineSeparator sectionRuleLine() {
        return new LineSeparator(new SolidLine(0.75f)).setStrokeColor(BORDER_COLOR).setMarginBottom(6)
                .setKeepWithNext(true);
    }

    /**
     * Stamps the footer rule + "Confidential" text + "Page N of {total}" on each page as it
     * finishes, rather than in a loop after the fact (see {@link #newDocument} for why the
     * latter crashes on multi-page documents). The total-page-count digits live in a small
     * shared {@link PdfFormXObject} placeholder that every page's footer references but that
     * only {@link #writeTotalPageCount} actually draws into, once the real total is known.
     */
    private static class FooterEventHandler implements IEventHandler {
        private static final float TOTAL_PLACEHOLDER_WIDTH = 22f;
        private static final float TOTAL_PLACEHOLDER_HEIGHT = 10f;

        private final PdfFormXObject totalPagesPlaceholder =
                new PdfFormXObject(new Rectangle(0, 0, TOTAL_PLACEHOLDER_WIDTH, TOTAL_PLACEHOLDER_HEIGHT));

        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            int pageNumber = pdfDoc.getPageNumber(page);
            Rectangle pageSize = page.getPageSize();
            float footerY = pageSize.getBottom() + 26;
            float pageLabelRight = pageSize.getRight() - MARGIN_SIDE - TOTAL_PLACEHOLDER_WIDTH;

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
                canvas.showTextAligned("Page " + pageNumber + " of ",
                        pageLabelRight, footerY, TextAlignment.RIGHT);
            }
            pdfCanvas.addXObjectAt(totalPagesPlaceholder, pageLabelRight, footerY - 2.5f);
            pdfCanvas.release();
        }

        void writeTotalPageCount(PdfDocument pdfDoc) {
            try (Canvas canvas = new Canvas(totalPagesPlaceholder, pdfDoc)) {
                canvas.setFont(regularFont()).setFontSize(8).setFontColor(TEXT_MUTED);
                canvas.showTextAligned(String.valueOf(pdfDoc.getNumberOfPages()), 0, 2.5f, TextAlignment.LEFT);
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
        addLabelCell(table, "Total Revenue (Pre-Discount)", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalRevenue()), TextAlignment.RIGHT, false);

        addLabelCell(table, "Services Revenue (Pre-Discount)", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalServicesRevenue()), TextAlignment.RIGHT, true);
        addLabelCell(table, "Products Revenue (Pre-Discount)", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.totalProductsRevenue()), TextAlignment.RIGHT, true);
        addBlankCell(table);
        addBlankCell(table);

        document.add(table);
    }

    private static void addTherapistEarningsTable(Document document, List<TherapistEarningsDTO> earnings) {
        Table table = newTable(new float[]{1.6f, 1, 1, 0.7f, 1, 1.1f, 0.7f, 1, 1, 1, 0.7f, 1, 1, 1}, 7.5f);

        addHeaderCell(table, "Therapist", TextAlignment.LEFT);
        addHeaderCell(table, "Svcs Rev.(All, Pre-Disc.)", TextAlignment.RIGHT);
        addHeaderCell(table, "Prod Rev.(All, Pre-Disc.)", TextAlignment.RIGHT);
        addHeaderCell(table, "Svcs(All)", TextAlignment.CENTER);
        addHeaderCell(table, "Svcs Rev.(Bonus, Pre-Disc.)", TextAlignment.RIGHT);
        addHeaderCell(table, "Prod Rev.(Comm., Pre-Disc.)", TextAlignment.RIGHT);
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
        addHeaderCell(table, "Revenue (Pre-Discount)", TextAlignment.RIGHT);

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
        addHeaderCell(table, "Revenue (Pre-Discount)", TextAlignment.RIGHT);
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
        addHeaderCell(table, "Revenue (Pre-Discount)", TextAlignment.RIGHT);
        addHeaderCell(table, "Avg Price (Pre-Discount)", TextAlignment.RIGHT);
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

    private static void addRevenueSummaryTable(Document document, RevenueSummaryDTO summary) {
        Table table = newTable(new float[]{2, 1, 2, 1, 2, 1}, 9.5f);

        addLabelCell(table, "Appointments", TextAlignment.LEFT);
        addDataCell(table, String.valueOf(summary.appointmentCount()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Gross Revenue", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.grossRevenue()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Net Revenue (Billed)", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.netRevenue()), TextAlignment.RIGHT, false);

        addLabelCell(table, "Combo Discounts", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.comboDiscount()), TextAlignment.RIGHT, true);
        addLabelCell(table, "Manual Discounts", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.manualDiscount()), TextAlignment.RIGHT, true);
        addLabelCell(table, "Collected", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.collected()), TextAlignment.RIGHT, true);

        addLabelCell(table, "Outstanding", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.outstanding()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Wallet-Funded", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.walletFunded()), TextAlignment.RIGHT, false);
        addLabelCell(table, "Advance Payments (Scheduled/Cancelled/No-Show)", TextAlignment.LEFT);
        addDataCell(table, formatCurrency(summary.advanceReceived()), TextAlignment.RIGHT, false);

        document.add(table);
    }

    private static Table buildPaymentMethodTable(List<RevenueByPaymentMethodDTO> byPaymentMethod) {
        Table table = newTable(new float[]{2, 1}, 9.5f);

        addHeaderCell(table, "Method", TextAlignment.LEFT);
        addHeaderCell(table, "Amount", TextAlignment.RIGHT);

        boolean shaded = false;
        for (RevenueByPaymentMethodDTO m : byPaymentMethod) {
            addDataCell(table, m.label(), TextAlignment.LEFT, shaded);
            addDataCell(table, formatCurrency(m.amount()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }
        return table;
    }

    private static Table buildRevenueByTherapistTable(List<RevenueByTherapistDTO> byTherapist) {
        Table table = newTable(new float[]{1.6f, 1, 1, 1}, 9.5f);

        addHeaderCell(table, "Therapist", TextAlignment.LEFT);
        addHeaderCell(table, "Gross Revenue", TextAlignment.RIGHT);
        addHeaderCell(table, "Discount", TextAlignment.RIGHT);
        addHeaderCell(table, "Net Revenue", TextAlignment.RIGHT);

        boolean shaded = false;
        for (RevenueByTherapistDTO t : byTherapist) {
            addDataCell(table, t.therapistName(), TextAlignment.LEFT, shaded);
            addDataCell(table, formatCurrency(t.grossRevenue()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(t.discountAmount()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(t.netRevenue()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }
        return table;
    }

    private static Table buildCatalogItemRevenueTable(List<RevenueByCatalogItemDTO> items) {
        Table table = newTable(new float[]{2, 2, 1, 1.2f}, 9.5f);

        addHeaderCell(table, "Name", TextAlignment.LEFT);
        addHeaderCell(table, "Tags", TextAlignment.LEFT);
        addHeaderCell(table, "Bookings", TextAlignment.CENTER);
        addHeaderCell(table, "Net Revenue", TextAlignment.RIGHT);

        boolean shaded = false;
        for (RevenueByCatalogItemDTO item : items) {
            addDataCell(table, item.name(), TextAlignment.LEFT, shaded);
            addDataCell(table, String.join(", ", item.tags()), TextAlignment.LEFT, shaded);
            addDataCell(table, String.valueOf(item.bookingsCount()), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(item.netRevenue()), TextAlignment.RIGHT, shaded);
            shaded = !shaded;
        }
        return table;
    }

    private static Table buildAppointmentRevenueRowsTable(List<AppointmentRevenueRowDTO> rows) {
        Table table = newTable(new float[]{1.3f, 1.3f, 1.3f, 0.9f, 1, 1, 1, 1, 1, 1}, 8f);

        addHeaderCell(table, "Date/Time", TextAlignment.LEFT);
        addHeaderCell(table, "Patient", TextAlignment.LEFT);
        addHeaderCell(table, "Therapist", TextAlignment.LEFT);
        addHeaderCell(table, "Status", TextAlignment.CENTER);
        addHeaderCell(table, "Gross", TextAlignment.RIGHT);
        addHeaderCell(table, "Discount", TextAlignment.RIGHT);
        addHeaderCell(table, "Net", TextAlignment.RIGHT);
        addHeaderCell(table, "Collected", TextAlignment.RIGHT);
        addHeaderCell(table, "Outstanding", TextAlignment.RIGHT);
        addHeaderCell(table, "Payment Method", TextAlignment.LEFT);

        boolean shaded = false;
        for (AppointmentRevenueRowDTO row : rows) {
            addDataCell(table, row.dateTime().format(ROW_DATETIME_FORMATTER), TextAlignment.LEFT, shaded);
            addDataCell(table, row.patientName(), TextAlignment.LEFT, shaded);
            addDataCell(table, row.therapistName(), TextAlignment.LEFT, shaded);
            addDataCell(table, row.status().name(), TextAlignment.CENTER, shaded);
            addDataCell(table, formatCurrency(row.gross()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(row.discount()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(row.net()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(row.collected()), TextAlignment.RIGHT, shaded);
            addDataCell(table, formatCurrency(row.outstanding()), TextAlignment.RIGHT, shaded);
            addDataCell(table, row.paymentMethod() != null ? row.paymentMethod().name() : "N/A", TextAlignment.LEFT, shaded);
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
