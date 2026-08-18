package com.clinic.healinghouse.util;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.Appointment;
import com.clinic.healinghouse.entity.AppointmentProductLine;
import com.clinic.healinghouse.entity.AppointmentServiceLine;
import com.clinic.healinghouse.entity.PatientPackage;
import com.clinic.healinghouse.entity.PatientPackageProductItem;
import com.clinic.healinghouse.entity.PatientPackageServiceItem;
import com.clinic.healinghouse.entity.PaymentMethod;
import com.clinic.healinghouse.entity.WalletTransaction;
import com.clinic.healinghouse.entity.WalletTransactionType;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.styledxmlparser.resolver.resource.IResourceRetriever;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Renders a printable/downloadable PDF for every place money actually changes hands in this app:
 * an invoice for a COMPLETED {@link Appointment}, an invoice for a {@link PatientPackage} sale, and
 * a receipt for a {@link WalletTransaction} TOP_UP/REFUND. Built the same way {@link
 * ContractPdfService} builds a contract PDF — arbitrary-but-controlled HTML converted via iText's
 * html2pdf add-on — rather than {@link PdfExportUtil}'s structured Paragraph/Table API, since these
 * are one-off branded layouts, not a repeating tabular report. Deliberately NOT persisted (unlike
 * EmploymentContract.pdfContent): every document here is always regenerated on demand straight from
 * the underlying record's current state, the same "generate on demand, never store" convention
 * every report PDF already follows — there's no equivalent here of a contract's "lock content at
 * approval" requirement. USAGE/REVERSAL wallet transactions are deliberately not covered — no money
 * physically moves on those (see WalletTransaction's own javadoc), so there's nothing to receipt.
 */
@Component
@RequiredArgsConstructor
public class InvoicePdfService {

    private final HealingHouseProperties properties;

    private String logoDataUri;
    private String stampDataUri;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    /** Blocks every live network/file resource fetch html2pdf might attempt — mirrors
     *  ContractPdfService's BLOCKING_RESOURCE_RETRIEVER. Nothing rendered here comes from
     *  user-editable rich text (unlike a contract body), but there's no reason to carry a live
     *  resource-fetch capability this class never legitimately needs. */
    private static final IResourceRetriever BLOCKING_RESOURCE_RETRIEVER = new IResourceRetriever() {
        @Override
        public InputStream getInputStreamByUrl(URL url) {
            return null;
        }

        @Override
        public byte[] getByteArrayByUrl(URL url) {
            return null;
        }
    };

    @PostConstruct
    void loadBrandImages() {
        logoDataUri = loadAsDataUri("/static/images/clinic_logo.png", "image/png");
        stampDataUri = loadAsDataUri("/static/images/clinic_stamp.png", "image/png");
    }

    // ── Appointment invoice ──────────────────────────────────────────────────

    public byte[] renderAppointmentInvoice(Appointment appt) {
        String html = "<html><head><meta charset=\"UTF-8\"><style>" + css() + "</style></head><body>"
                + letterheadHtml("INVOICE", "INV-A-" + appt.getId(),
                        appt.getCompletedAt() != null ? appt.getCompletedAt() : appt.getAppointmentDateTime())
                + partiesHtml(appt)
                + appointmentItemsTableHtml(appt)
                + appointmentTotalsHtml(appt)
                + footerHtml()
                + "</body></html>";
        return convert(html);
    }

    private String partiesHtml(Appointment appt) {
        var patient = appt.getPatient();
        StringBuilder billTo = new StringBuilder();
        billTo.append("<div class=\"party-name\">").append(escape(patient.getFullName())).append("</div>");
        if (patient.getPhone() != null && !patient.getPhone().isBlank()) {
            billTo.append("<div>").append(escape(patient.getPhone())).append("</div>");
        }
        if (patient.getAddress() != null && !patient.getAddress().isBlank()) {
            billTo.append("<div>").append(escape(patient.getAddress())).append("</div>");
        }

        StringBuilder details = new StringBuilder();
        details.append(detailRow("Appointment Date", appt.getAppointmentDateTime().format(DATE_TIME_FMT)));
        details.append(detailRow("Therapist", appt.getTherapist().getFullName()));
        if (appt.getPaymentMethod() != null) {
            details.append(detailRow("Payment Method", paymentMethodLabel(appt.getPaymentMethod())));
        }
        details.append(detailRow("Status", appt.getPaymentStatus()));

        return "<table class=\"parties\"><tr>"
                + "<td class=\"party-block\"><div class=\"party-label\">Bill To</div>" + billTo + "</td>"
                + "<td class=\"party-block\"><div class=\"party-label\">Invoice Details</div>"
                + "<table class=\"detail-rows\">" + details + "</table></td>"
                + "</tr></table>";
    }

    private String appointmentItemsTableHtml(Appointment appt) {
        StringBuilder rows = new StringBuilder();
        for (AppointmentServiceLine sl : appt.getServiceLines()) {
            rows.append(itemRow(sl.getService().getName()
                            + (sl.getAppointmentCombo() != null ? " <span class=\"tag\">Combo</span>" : "")
                            + (sl.getPackageServiceItem() != null ? " <span class=\"tag tag-package\">Package</span>" : ""),
                    sl.getQuantity(), sl.getPriceAtTime(), sl.getEffectiveLineTotal()));
        }
        for (AppointmentProductLine pl : appt.getProductLines()) {
            rows.append(itemRow(pl.getProduct().getName()
                            + (pl.getAppointmentCombo() != null ? " <span class=\"tag\">Combo</span>" : "")
                            + (pl.getPackageProductItem() != null ? " <span class=\"tag tag-package\">Package</span>" : ""),
                    pl.getQuantity(), pl.getPriceAtTime(), pl.getEffectiveLineTotal()));
        }
        if (rows.isEmpty()) {
            rows.append("<tr><td colspan=\"4\" class=\"text-center text-muted\">No items recorded.</td></tr>");
        }
        return "<table class=\"items\">"
                + "<thead><tr><th>Description</th><th class=\"text-center\">Qty</th>"
                + "<th class=\"text-end\">Rate</th><th class=\"text-end\">Amount</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>";
    }

    private String itemRow(String description, int qty, BigDecimal rate, BigDecimal amount) {
        return "<tr><td>" + description + "</td>"
                + "<td class=\"text-center\">" + qty + "</td>"
                + "<td class=\"text-end\">" + money(rate) + "</td>"
                + "<td class=\"text-end\">" + money(amount) + "</td></tr>";
    }

    private String appointmentTotalsHtml(Appointment appt) {
        BigDecimal rawSubtotal = appt.getTotalServiceAmount().add(appt.getTotalProductAmount());
        BigDecimal totalSavings = appt.getTotalComboDiscount().add(appt.getDiscountAmount() != null ? appt.getDiscountAmount() : BigDecimal.ZERO);

        StringBuilder rows = new StringBuilder();
        rows.append(totalRow("Subtotal", money(rawSubtotal), false));
        if (totalSavings.signum() > 0) {
            rows.append(totalRow("Discounts &amp; Combo Savings", "- " + money(totalSavings), false));
        }
        rows.append(totalRow("Grand Total", money(appt.getGrandTotal()), true));
        rows.append(totalRow("Amount Paid", money(appt.getAmountPaid()), false));
        if (appt.getWalletAmountApplied() != null && appt.getWalletAmountApplied().signum() > 0) {
            rows.append(totalRow("&nbsp;&nbsp;· from Wallet", money(appt.getWalletAmountApplied()), false));
        }
        if (appt.getPackageAmountApplied() != null && appt.getPackageAmountApplied().signum() > 0) {
            rows.append(totalRow("&nbsp;&nbsp;· from Package", money(appt.getPackageAmountApplied()), false));
        }
        rows.append(totalRow("Balance Due", money(appt.getBalanceDue()), false));

        return "<table class=\"totals-wrap\"><tr><td></td><td class=\"totals-box\">"
                + "<table class=\"totals\">" + rows + "</table></td></tr></table>";
    }

    // ── Package sale invoice ─────────────────────────────────────────────────

    public byte[] renderPackageInvoice(PatientPackage pkg, PaymentMethod paymentMethod) {
        String html = "<html><head><meta charset=\"UTF-8\"><style>" + css() + "</style></head><body>"
                + letterheadHtml("PACKAGE INVOICE", "INV-P-" + pkg.getId(), pkg.getPurchasedAt())
                + packagePartiesHtml(pkg, paymentMethod)
                + packageItemsTableHtml(pkg)
                + packageTotalsHtml(pkg)
                + footerHtml()
                + "</body></html>";
        return convert(html);
    }

    private String packagePartiesHtml(PatientPackage pkg, PaymentMethod paymentMethod) {
        var patient = pkg.getPatient();
        StringBuilder billTo = new StringBuilder();
        billTo.append("<div class=\"party-name\">").append(escape(patient.getFullName())).append("</div>");
        if (patient.getPhone() != null && !patient.getPhone().isBlank()) {
            billTo.append("<div>").append(escape(patient.getPhone())).append("</div>");
        }
        if (patient.getAddress() != null && !patient.getAddress().isBlank()) {
            billTo.append("<div>").append(escape(patient.getAddress())).append("</div>");
        }

        StringBuilder details = new StringBuilder();
        details.append(detailRow("Package", pkg.getName()));
        if (pkg.getExpiryDate() != null) {
            details.append(detailRow("Valid Until", pkg.getExpiryDate().format(DATE_FMT)));
        }
        if (paymentMethod != null) {
            details.append(detailRow("Payment Method", paymentMethodLabel(paymentMethod)));
        }
        details.append(detailRow("Status", "PAID IN FULL"));

        return "<table class=\"parties\"><tr>"
                + "<td class=\"party-block\"><div class=\"party-label\">Bill To</div>" + billTo + "</td>"
                + "<td class=\"party-block\"><div class=\"party-label\">Invoice Details</div>"
                + "<table class=\"detail-rows\">" + details + "</table></td>"
                + "</tr></table>";
    }

    private String packageItemsTableHtml(PatientPackage pkg) {
        StringBuilder rows = new StringBuilder();
        for (PatientPackageServiceItem item : pkg.getServiceItems()) {
            rows.append(packageItemRow(item.getService().getName(), item.getSessionsTotal(), item.getPriceAllocated()));
        }
        for (PatientPackageProductItem item : pkg.getProductItems()) {
            rows.append(packageItemRow(item.getProduct().getName(), item.getSessionsTotal(), item.getPriceAllocated()));
        }
        if (rows.isEmpty()) {
            rows.append("<tr><td colspan=\"4\" class=\"text-center text-muted\">No items recorded.</td></tr>");
        }
        return "<table class=\"items\">"
                + "<thead><tr><th>Description</th><th class=\"text-center\">Sessions</th>"
                + "<th class=\"text-end\">Rate / Session</th><th class=\"text-end\">Amount</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>";
    }

    private String packageItemRow(String name, int sessions, BigDecimal priceAllocated) {
        BigDecimal perSession = sessions > 0
                ? priceAllocated.divide(BigDecimal.valueOf(sessions), 2, RoundingMode.HALF_UP)
                : priceAllocated;
        return "<tr><td>" + escape(name) + "</td>"
                + "<td class=\"text-center\">" + sessions + "</td>"
                + "<td class=\"text-end\">" + money(perSession) + "</td>"
                + "<td class=\"text-end\">" + money(priceAllocated) + "</td></tr>";
    }

    private String packageTotalsHtml(PatientPackage pkg) {
        StringBuilder rows = new StringBuilder();
        rows.append(totalRow("Total Price", money(pkg.getTotalPrice()), true));
        rows.append(totalRow("Amount Paid", money(pkg.getTotalPrice()), false));
        rows.append(totalRow("Balance Due", money(BigDecimal.ZERO), false));
        return "<table class=\"totals-wrap\"><tr><td></td><td class=\"totals-box\">"
                + "<table class=\"totals\">" + rows + "</table></td></tr></table>";
    }

    // ── Wallet top-up / refund receipt ───────────────────────────────────────

    public byte[] renderWalletReceipt(WalletTransaction txn) {
        boolean isRefund = txn.getType() == WalletTransactionType.REFUND;
        String docTitle = isRefund ? "WALLET REFUND RECEIPT" : "WALLET TOP-UP RECEIPT";
        String html = "<html><head><meta charset=\"UTF-8\"><style>" + css() + "</style></head><body>"
                + letterheadHtml(docTitle, "RCT-W-" + txn.getId(), txn.getCreatedAt())
                + walletReceiptPartiesHtml(txn)
                + walletReceiptItemsHtml(txn, isRefund)
                + walletReceiptTotalsHtml(txn, isRefund)
                + footerHtml()
                + "</body></html>";
        return convert(html);
    }

    private String walletReceiptPartiesHtml(WalletTransaction txn) {
        var patient = txn.getPatient();
        StringBuilder billTo = new StringBuilder();
        billTo.append("<div class=\"party-name\">").append(escape(patient.getFullName())).append("</div>");
        if (patient.getPhone() != null && !patient.getPhone().isBlank()) {
            billTo.append("<div>").append(escape(patient.getPhone())).append("</div>");
        }
        if (patient.getAddress() != null && !patient.getAddress().isBlank()) {
            billTo.append("<div>").append(escape(patient.getAddress())).append("</div>");
        }

        StringBuilder details = new StringBuilder();
        details.append(detailRow("Payment Method", paymentMethodLabel(txn.getPaymentMethod())));
        if (txn.getNote() != null && !txn.getNote().isBlank()) {
            details.append(detailRow("Note", escape(txn.getNote())));
        }

        return "<table class=\"parties\"><tr>"
                + "<td class=\"party-block\"><div class=\"party-label\">Received From / Paid To</div>" + billTo + "</td>"
                + "<td class=\"party-block\"><div class=\"party-label\">Receipt Details</div>"
                + "<table class=\"detail-rows\">" + details + "</table></td>"
                + "</tr></table>";
    }

    private String walletReceiptItemsHtml(WalletTransaction txn, boolean isRefund) {
        String description = isRefund ? "Refund of Prepaid Wallet Balance" : "Top-Up of Prepaid Wallet Balance";
        return "<table class=\"items\">"
                + "<thead><tr><th>Description</th><th class=\"text-end\">Amount</th></tr></thead>"
                + "<tbody><tr><td>" + escape(description) + "</td>"
                + "<td class=\"text-end\">" + money(txn.getAmount()) + "</td></tr></tbody></table>";
    }

    private String walletReceiptTotalsHtml(WalletTransaction txn, boolean isRefund) {
        String label = isRefund ? "Amount Refunded" : "Amount Received";
        StringBuilder rows = new StringBuilder();
        rows.append(totalRow(label, money(txn.getAmount()), true));
        return "<table class=\"totals-wrap\"><tr><td></td><td class=\"totals-box\">"
                + "<table class=\"totals\">" + rows + "</table></td></tr></table>";
    }

    // ── Shared layout pieces ─────────────────────────────────────────────────

    private String letterheadHtml(String docTitle, String invoiceNumber, LocalDateTime invoiceDate) {
        return "<table class=\"letterhead\"><tr>"
                + "<td class=\"brand\">"
                + (logoDataUri != null ? "<img src=\"" + logoDataUri + "\">" : "")
                + "<div class=\"clinic-name\">" + escape(clinicName()) + "</div>"
                + "<div class=\"clinic-meta\">" + escape(clinicAddress()) + "</div>"
                + "<div class=\"clinic-meta\">" + escape(clinicPhone()) + " | " + escape(clinicEmail()) + "</div>"
                + "</td>"
                + "<td class=\"doc-meta\">"
                + "<div class=\"doc-title\">" + escape(docTitle) + "</div>"
                + "<div class=\"doc-row\"><span>Invoice No.</span><strong>" + escape(invoiceNumber) + "</strong></div>"
                + "<div class=\"doc-row\"><span>Date</span><strong>" + invoiceDate.format(DATE_FMT) + "</strong></div>"
                + "</td>"
                + "</tr></table>";
    }

    private String footerHtml() {
        return "<table class=\"footer\"><tr>"
                + "<td class=\"footer-note\">"
                + "<div class=\"thanks\">Thank you for choosing " + escape(clinicName()) + "!</div>"
                + "<div class=\"fine-print\">This is a system-generated document and does not require a signature.</div>"
                + "</td>"
                + "<td class=\"footer-stamp\">"
                + (stampDataUri != null ? "<img class=\"stamp\" src=\"" + stampDataUri + "\">" : "")
                + "</td>"
                + "</tr></table>";
    }

    private String detailRow(String label, String value) {
        return "<tr><td class=\"detail-label\">" + escape(label) + "</td><td class=\"detail-value\">" + value + "</td></tr>";
    }

    private String totalRow(String label, String value, boolean emphasize) {
        String cls = emphasize ? "totals-row totals-row-emphasis" : "totals-row";
        return "<tr class=\"" + cls + "\"><td>" + label + "</td><td class=\"text-end\">" + value + "</td></tr>";
    }

    private String paymentMethodLabel(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Cash";
            case UPI -> "UPI";
            case BANK_TRANSFER -> "Bank Transfer";
            case CARD -> "Card";
            case OTHER -> "Other";
        };
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        NumberFormat fmt = NumberFormat.getInstance(new Locale("en", "IN"));
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        return properties.getCurrency().getSymbol() + " " + fmt.format(value);
    }

    private String clinicName() {
        return properties.getContracts().getClinicName();
    }

    private String clinicAddress() {
        return properties.getContracts().getClinicAddress();
    }

    private String clinicPhone() {
        return properties.getContracts().getClinicPhone();
    }

    private String clinicEmail() {
        return properties.getContracts().getClinicEmail();
    }

    private byte[] convert(String html) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConverterProperties converterProperties = new ConverterProperties();
        converterProperties.setResourceRetriever(BLOCKING_RESOURCE_RETRIEVER);
        HtmlConverter.convertToPdf(html, out, converterProperties);
        return out.toByteArray();
    }

    private String css() {
        return "@page { margin: 55px 45px; } "
                + "body { font-family: Helvetica, Arial, sans-serif; font-size: 11px; color: #333333; } "
                + "table { border-collapse: collapse; width: 100%; } "
                + ".letterhead { border-bottom: 2px solid #AE2E2B; padding-bottom: 12px; margin-bottom: 18px; } "
                + ".letterhead .brand { width: 54%; vertical-align: top; } "
                + ".letterhead .brand img { height: 46px; margin-bottom: 4px; } "
                + ".letterhead .clinic-name { font-size: 16px; font-weight: bold; color: #6F201C; } "
                + ".letterhead .clinic-meta { font-size: 9.5px; color: #888888; } "
                + ".letterhead .doc-meta { width: 46%; vertical-align: top; text-align: right; } "
                + ".letterhead .doc-title { font-size: 17px; font-weight: bold; color: #6F201C; letter-spacing: 1px; margin-bottom: 8px; white-space: nowrap; } "
                + ".letterhead .doc-row { font-size: 10.5px; margin-bottom: 2px; } "
                + ".letterhead .doc-row span { color: #888888; margin-right: 8px; } "
                + ".parties { margin-bottom: 18px; } "
                + ".party-block { width: 50%; vertical-align: top; padding-right: 10px; } "
                + ".party-label { font-size: 9.5px; font-weight: bold; color: #AE2E2B; text-transform: uppercase; letter-spacing: .5px; margin-bottom: 4px; } "
                + ".party-name { font-weight: bold; font-size: 12px; margin-bottom: 2px; } "
                + ".detail-rows { width: 100%; } "
                + ".detail-label { color: #888888; padding: 1px 8px 1px 0; white-space: nowrap; } "
                + ".detail-value { font-weight: bold; text-align: right; } "
                + "table.items { margin-bottom: 4px; } "
                + "table.items thead th { background: #6F201C; color: #ffffff; padding: 7px 8px; font-size: 10px; text-transform: uppercase; letter-spacing: .5px; text-align: left; } "
                + "table.items tbody td { padding: 7px 8px; border-bottom: 1px solid #e5e5e5; } "
                + "table.items tbody tr:nth-child(even) { background: #faf7f2; } "
                + ".tag { display: inline-block; font-size: 8.5px; font-weight: bold; color: #6F201C; background: #f0e2c8; border-radius: 3px; padding: 1px 5px; margin-left: 4px; } "
                + ".tag-package { color: #1f6b3d; background: #ddf0e2; } "
                + ".text-center { text-align: center; } "
                + ".text-end { text-align: right; } "
                + ".text-muted { color: #999999; padding: 14px 0; } "
                + ".totals-wrap { margin-top: 4px; } "
                + ".totals-box { width: 46%; } "
                + "table.totals { width: 100%; } "
                + ".totals-row td { padding: 4px 8px; font-size: 11px; } "
                + ".totals-row td:first-child { color: #666666; } "
                + ".totals-row-emphasis { border-top: 1.5px solid #333333; border-bottom: 1.5px solid #333333; } "
                + ".totals-row-emphasis td { font-weight: bold; font-size: 13px; color: #6F201C; padding: 6px 8px; } "
                + ".footer { margin-top: 40px; border-top: 1px solid #dddddd; padding-top: 12px; } "
                + ".footer-note { width: 75%; vertical-align: middle; } "
                + ".thanks { font-weight: bold; color: #6F201C; font-size: 12px; margin-bottom: 2px; } "
                + ".fine-print { font-size: 9px; color: #999999; } "
                + ".footer-stamp { width: 25%; text-align: right; vertical-align: middle; } "
                + ".footer-stamp img.stamp { height: 65px; opacity: .9; }";
    }

    private String loadAsDataUri(String classpathPath, String mimeType) {
        try (InputStream in = InvoicePdfService.class.getResourceAsStream(classpathPath)) {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
