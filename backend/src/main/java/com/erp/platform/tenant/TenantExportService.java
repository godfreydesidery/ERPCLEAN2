package com.erp.platform.tenant;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streams every row of one organisation as a portable zip archive (MULTITENANCY-PLAN §14.2 R-7).
 *
 * <p>The archive holds one {@code data/<table>.csv} per tenant table (header row, RFC 4180) and a
 * {@code manifest.json}. CSV was chosen over JSON Lines because the recipient is a departing
 * customer, not a program: every spreadsheet opens it. It is lossless here because every value is
 * read as PostgreSQL's own text form ({@code col::text}: exact numerics, ISO timestamps with zone,
 * JSON as JSON text), NULL is an empty unquoted field and an empty string is {@code ""}.
 */
public interface TenantExportService {

    /**
     * Writes the archive to {@code out}. The data is read in ONE read-only REPEATABLE READ
     * transaction, so every table comes from the same snapshot; rows are streamed with a JDBC fetch
     * size and no table is ever held in memory.
     *
     * <p>{@code beforeFinish} runs after the last row has been read and the transaction has ended,
     * and BEFORE the zip's central directory is written. The caller records its audit row there: if
     * that fails the archive is left truncated (unreadable) rather than "completed but unaudited".
     *
     * @throws IOException when {@code out} fails
     */
    ExportResult export(TenantRef organisation, OutputStream out, Consumer<ExportResult> beforeFinish)
            throws IOException;

    /**
     * What an export contained.
     *
     * @param flywayVersion latest applied migration version (as recorded in the manifest)
     * @param exportedAt    when the snapshot was taken
     * @param rowCounts     rows written per table, in archive order
     * @param redacted      redacted columns per table ({@code table.column}), omitted from the data
     * @param globalTables  tables excluded as global
     */
    record ExportResult(String flywayVersion, Instant exportedAt, Map<String, Long> rowCounts,
                        long totalRows, java.util.List<String> redacted,
                        java.util.List<String> globalTables) {
    }
}
