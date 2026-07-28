package com.clinic.healinghouse.util;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Header-aware CSV row reader for bulk-import features (services/products catalog upload).
 *  Headers are matched case-insensitively and trimmed, so a column order/casing mismatch never
 *  silently misreads data the way positional parsing would. */
@Component
public class CsvImportUtil {

    public record CsvRow(int rowNumber, Map<String, String> values) {
        public String get(String header) {
            return values.get(header.toLowerCase());
        }
    }

    public List<CsvRow> readRows(MultipartFile file) throws IOException, CsvValidationException {
        List<CsvRow> rows = new ArrayList<>();
        try (Reader in = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVReader reader = new CSVReader(in)) {
            String[] headerLine = reader.readNext();
            if (headerLine == null) return rows;
            String[] headers = Arrays.stream(headerLine)
                    .map(h -> h == null ? "" : h.trim().toLowerCase())
                    .toArray(String[]::new);

            String[] line;
            int rowNumber = 1;
            while ((line = reader.readNext()) != null) {
                rowNumber++;
                if (line.length == 1 && !StringUtils.hasText(line[0])) continue; // blank line
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    String value = i < line.length && line[i] != null ? line[i].trim() : "";
                    values.put(headers[i], value);
                }
                rows.add(new CsvRow(rowNumber, values));
            }
        }
        return rows;
    }

    /** @throws IllegalArgumentException with a row-facing message on blank/non-positive/unparseable input. */
    public static BigDecimal parsePrice(String raw) {
        if (!StringUtils.hasText(raw)) throw new IllegalArgumentException("Price is required");
        BigDecimal price;
        try {
            price = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid price: '" + raw + "'");
        }
        if (price.compareTo(new BigDecimal("0.01")) < 0) {
            throw new IllegalArgumentException("Price must be at least 0.01");
        }
        return price;
    }

    /** Blank input returns {@code defaultValue}; a negative or unparseable value is rejected. */
    public static int parseOptionalNonNegativeInt(String raw, int defaultValue, String fieldLabel) {
        if (!StringUtils.hasText(raw)) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + fieldLabel + ": '" + raw + "'");
        }
        if (value < 0) throw new IllegalArgumentException(fieldLabel + " cannot be negative");
        return value;
    }

    /** Blank input returns {@code true} (the entity default) — "active" is opt-out, not opt-in. */
    public static boolean parseActive(String raw) {
        if (!StringUtils.hasText(raw)) return true;
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("Invalid active value: '" + raw + "' (use true/false)");
        };
    }

    /** Multiple tag names within one CSV field are semicolon-separated (a plain comma is already the
     *  CSV column delimiter). Blank input yields no tags. */
    public static List<String> parseTags(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
