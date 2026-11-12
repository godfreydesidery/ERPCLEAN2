package com.erp.platform.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TenantExportServiceImpl implements TenantExportService {

    private static final Logger log = LoggerFactory.getLogger(TenantExportServiceImpl.class);

    /** Rows pulled from the server per round trip; the only buffer a table ever occupies. */
    private static final int FETCH_SIZE = 1000;

    /**
     * A plain mapper, deliberately NOT the application's: that one writes every Long as a JSON
     * string (so ids survive JavaScript), which would turn the manifest's row counts into strings.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TenantDataCatalog catalog;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final Clock clock;

    TenantExportServiceImpl(TenantDataCatalog catalog, JdbcTemplate jdbc,
                            PlatformTransactionManager txManager, Clock clock) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.txManager = txManager;
        this.clock = clock;
    }

    @Override
    public ExportResult export(TenantRef org, OutputStream out, Consumer<ExportResult> beforeFinish)
            throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        ExportResult result;
        try {
            result = tx.execute(status -> {
                try {
                    return writeSnapshot(org, zip);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        // The snapshot transaction is over. Audit now, before the archive becomes valid.
        beforeFinish.accept(result);
        zip.finish();
        out.flush();
        log.info("Exported organisation {}: {} tables, {} rows", org.id(), result.rowCounts().size(),
                result.totalRows());
        return result;
    }

    private ExportResult writeSnapshot(TenantRef org, ZipOutputStream zip) throws IOException {
        // Fail closed if the transaction is not what the consistency claim needs. Spring honours
        // the definition on a JPA transaction manager, but a pooled connection that silently kept
        // READ COMMITTED would produce a torn export that LOOKS fine.
        String isolation = jdbc.queryForObject("SHOW transaction_isolation", String.class);
        String readOnly = jdbc.queryForObject("SHOW transaction_read_only", String.class);
        if (!"repeatable read".equals(isolation) || !"on".equals(readOnly)) {
            log.error("Export refused: transaction is '{}' / read_only={}", isolation, readOnly);
            throw new IllegalStateException("Export transaction was not read-only repeatable read.");
        }

        Instant exportedAt = clock.instant();
        TenantDataPlan plan = catalog.planFor(org.id());
        String flyway = flywayVersion();

        // Archive order: alphabetical, so a human can find a file. (The plan is in deletion order.)
        List<TenantTable> tables = new ArrayList<>(plan.tables());
        tables.sort((a, b) -> a.name().compareTo(b.name()));

        Map<String, Long> counts = new LinkedHashMap<>();
        List<Map<String, Object>> manifestTables = new ArrayList<>();
        List<String> redacted = new ArrayList<>();
        long total = 0;

        for (TenantTable table : tables) {
            long rows = writeTable(zip, table);
            counts.put(table.name(), rows);
            total += rows;
            table.redactedColumns().forEach(c -> redacted.add(table.name() + "." + c));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table", table.name());
            entry.put("classification", table.kind().name().toLowerCase());
            entry.put("file", "data/" + table.name() + ".csv");
            entry.put("rows", rows);
            entry.put("columns", table.exportColumns());
            entry.put("redactedColumns", table.redactedColumns());
            manifestTables.add(entry);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "csv-rfc4180; null = empty unquoted field; empty string = \"\"; "
                + "values are PostgreSQL text output");
        manifest.put("organisationUid", org.uid());
        manifest.put("organisationName", org.name());
        manifest.put("exportedAt", exportedAt.toString());
        manifest.put("flywayVersion", flyway);
        manifest.put("totalRows", total);
        manifest.put("redactedColumns", redacted);
        manifest.put("excludedGlobalTables", plan.globalTables());
        manifest.put("tables", manifestTables);

        // The manifest is the LAST entry: the counts only exist once every table has streamed.
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.closeEntry();

        return new ExportResult(flyway, exportedAt, counts, total, List.copyOf(redacted),
                plan.globalTables());
    }

    private long writeTable(ZipOutputStream zip, TenantTable table) throws IOException {
        zip.putNextEntry(new ZipEntry("data/" + table.name() + ".csv"));
        // Closing the writer must not close the zip: wrap it so close() only flushes.
        Writer w = new OutputStreamWriter(new FilterOutputStream(zip) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        }, StandardCharsets.UTF_8);

        List<String> cols = table.exportColumns();
        writeRow(w, cols);

        String select = cols.isEmpty() ? "1" : cols.stream()
                .map(c -> TenantDataCatalogImpl.q(c) + "::text")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String order = table.primaryKey().isEmpty() ? "" : " ORDER BY " + table.primaryKey().stream()
                .map(TenantDataCatalogImpl::q).reduce((a, b) -> a + ", " + b).orElseThrow();
        String sql = "SELECT " + select + " FROM " + TenantDataCatalogImpl.q(table.name())
                + " WHERE " + table.rowFilterSql() + order;

        long rows = jdbc.execute((ConnectionCallback<Long>) con -> {
            long n = 0;
            try (PreparedStatement ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(FETCH_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> row = new ArrayList<>(cols.size());
                    while (rs.next()) {
                        row.clear();
                        for (int i = 1; i <= cols.size(); i++) {
                            row.add(rs.getString(i));
                        }
                        writeRow(w, row);
                        n++;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return n;
        });

        w.flush();
        zip.closeEntry();
        return rows;
    }

    /** RFC 4180 row. A null is an empty unquoted field; an empty string is {@code ""}. */
    private static void writeRow(Writer w, List<String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = values.get(i);
            if (v == null) {
                continue;
            }
            boolean quote = v.isEmpty() || v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                    || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
            if (quote) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    private String flywayVersion() {
        // No try/catch: a failed statement aborts the surrounding PostgreSQL transaction, so
        // swallowing it would only turn this into a confusing failure on the next table.
        List<String> v = jdbc.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
        return v.isEmpty() ? "unknown" : v.get(0);
    }
}
