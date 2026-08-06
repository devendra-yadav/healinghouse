package com.clinic.healinghouse.dto;

import java.io.Serializable;
import java.util.List;

/** Result of a best-effort CSV bulk import (see TreatmentService/ProductService#importFromCsv) —
 *  valid rows are inserted, invalid/duplicate rows are skipped and reported individually so staff
 *  can fix and re-upload just the problem rows. Serializable so it can ride as a flash attribute
 *  across the post-redirect-get back to the list page. */
public record CsvImportResultDTO(
        int totalRows,
        int successCount,
        int skippedCount,
        int errorCount,
        List<RowResult> rows
) implements Serializable {

    public enum RowStatus { SUCCESS, SKIPPED, ERROR }

    public record RowResult(int rowNumber, String name, RowStatus status, String message) implements Serializable {
    }
}