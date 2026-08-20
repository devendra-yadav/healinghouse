package com.clinic.healinghouse.dto;

import java.util.List;

/**
 * A titled block of label/value lines rendered identically at the top of every CSV/PDF export —
 * typically "Filters Applied" (what was selected on screen when the export was generated) and/or
 * "Summary" (totals/counts for the exported rows). Sharing one shape across every export keeps
 * their look consistent instead of each report inventing its own layout.
 */
public record ExportInfoBlock(String title, List<Line> lines) {

    public record Line(String label, String value) {
    }

    public static ExportInfoBlock of(String title, Line... lines) {
        return new ExportInfoBlock(title, List.of(lines));
    }

    /** For blocks assembled conditionally (e.g. "Filters Applied", where a line is only added if that
     *  filter was actually selected) — returns null rather than an empty/title-only block when nothing
     *  qualified, so the caller can drop the section from the export entirely. */
    public static ExportInfoBlock ofLines(String title, List<Line> lines) {
        return (lines == null || lines.isEmpty()) ? null : new ExportInfoBlock(title, lines);
    }

    public static Line line(String label, String value) {
        return new Line(label, value);
    }
}
