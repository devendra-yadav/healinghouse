package com.clinic.healinghouse.util;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.entity.EmploymentContract;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * Renders an APPROVED {@link EmploymentContract}'s current {@code contractBodyHtml} into the final
 * branded PDF (requirements/Employment_Contracts_Requirements_v1.md §5.4, §7, §9) — clinic
 * letterhead + the owner-edited body + an Owner signature/clinic stamp block, no employee signature
 * (§5.7). Kept separate from {@link PdfExportUtil}: that class lays out structured DTOs via iText's
 * table/paragraph API for tabular reports, a different technique and responsibility from converting
 * arbitrary owner-edited HTML via the html2pdf add-on. No ThreadLocal font juggling is needed here —
 * html2pdf's per-conversion font handling doesn't have the cross-document font-binding hazard that
 * forces that pattern in PdfExportUtil.
 */
@Component
@RequiredArgsConstructor
public class ContractPdfService {

    private final HealingHouseProperties properties;

    private String logoDataUri;
    private String stampDataUri;
    private String signatureDataUri;

    @PostConstruct
    void loadBrandImages() {
        logoDataUri = loadAsDataUri("/static/images/clinic_logo.png", "image/png");
        stampDataUri = loadAsDataUri("/static/images/clinic_stamp.png", "image/png");
        signatureDataUri = loadAsDataUri("/static/images/owner_signature.png", "image/png");
    }

    public byte[] render(EmploymentContract contract) {
        String html = buildFullHtml(contract);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlConverter.convertToPdf(html, out, new ConverterProperties());
        return out.toByteArray();
    }

    private String buildFullHtml(EmploymentContract contract) {
        HealingHouseProperties.Contracts cfg = properties.getContracts();
        return "<html><head><meta charset=\"UTF-8\"><style>" + css() + "</style></head><body>"
                + letterheadHtml(cfg)
                + contract.getContractBodyHtml()
                + signatureBlockHtml(cfg, contract)
                + "</body></html>";
    }

    private String css() {
        return "@page { margin: 60px 50px; } "
                + "body { font-family: Helvetica, Arial, sans-serif; font-size: 11px; color: #333333; } "
                + "h2 { color: #6F201C; margin-bottom: 4px; } "
                + "h4 { color: #AE2E2B; margin-top: 16px; margin-bottom: 4px; } "
                + "p { line-height: 1.5; } "
                + "ul { margin: 4px 0 12px 0; padding-left: 20px; } "
                + "li { line-height: 1.6; margin-bottom: 3px; } "
                + ".letterhead { text-align: center; border-bottom: 2px solid #AE2E2B; padding-bottom: 10px; margin-bottom: 20px; } "
                + ".letterhead img { height: 48px; } "
                + ".letterhead .clinic-name { font-size: 16px; font-weight: bold; color: #6F201C; margin-top: 6px; } "
                + ".letterhead .clinic-address { font-size: 10px; color: #888888; } "
                + ".letterhead .clinic-contact { font-size: 10px; color: #888888; } "
                + ".signature-block { margin-top: 60px; } "
                + ".signature-block img.sig { height: 45px; } "
                + ".signature-block img.stamp { height: 70px; margin-left: 20px; } "
                + ".signature-block .caption { border-top: 1px solid #333333; margin-top: 4px; padding-top: 4px; width: 260px; }";
    }

    private String letterheadHtml(HealingHouseProperties.Contracts cfg) {
        return "<div class=\"letterhead\">"
                + (logoDataUri != null ? "<img src=\"" + logoDataUri + "\">" : "")
                + "<div class=\"clinic-name\">" + escape(cfg.getClinicName()) + "</div>"
                + "<div class=\"clinic-address\">" + escape(cfg.getClinicAddress()) + "</div>"
                + "<div class=\"clinic-contact\">" + escape(cfg.getClinicPhone()) + " | " + escape(cfg.getClinicEmail()) + "</div>"
                + "</div>";
    }

    private String signatureBlockHtml(HealingHouseProperties.Contracts cfg, EmploymentContract contract) {
        return "<div class=\"signature-block\">"
                + "<p>For " + escape(cfg.getClinicName()) + "</p>"
                + (signatureDataUri != null ? "<img class=\"sig\" src=\"" + signatureDataUri + "\">" : "")
                + (stampDataUri != null ? "<img class=\"stamp\" src=\"" + stampDataUri + "\">" : "")
                + "<div class=\"caption\">Authorized Signatory</div>"
                + "</div>";
    }

    private String loadAsDataUri(String classpathPath, String mimeType) {
        try (InputStream in = ContractPdfService.class.getResourceAsStream(classpathPath)) {
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
