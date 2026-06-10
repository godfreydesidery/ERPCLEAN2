package com.erp.modules.reporting.export;

/**
 * The byte payload + HTTP metadata returned by {@link ReportExporter} (ADR-0018 D-9).
 */
public record ExportResult(
        byte[] content,
        String contentType,
        String filename
) {}
