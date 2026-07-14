package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Orchestration behaviour of {@link BulkImportService}: per-row dispatch, error isolation (a bad row
 * becomes an ERROR outcome, others still run), SKIP handling, and report aggregation. A no-op
 * transaction manager stands in for the real one (the validate path runs each row in a transaction).
 */
class BulkImportServiceTest {

    private BulkImportService service;

    @BeforeEach
    void setUp() {
        service = new BulkImportService(List.of(new FakeHandler(), new OtherHandler()),
                new XlsxTemplateWriter(), new XlsxRowReader(), new NoopTxManager());
        RequestContext.set(new RequestContext.Principal(1L, "tester", false, 10L, 20L, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void run_isolatesBadRows_countsCreatesSkipsAndErrors() {
        byte[] xlsx = sheet(List.of("* Name"),
                List.of(List.of("Alpha"), List.of("FAIL"), List.of("SKIP"), List.of("Beta")));
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx", null, xlsx);

        ImportReport report = service.run("test", file, ImportMode.VALIDATE);

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.errors()).isEqualTo(1);
        RowOutcome bad = report.rows().stream()
                .filter(r -> r.action() == RowAction.ERROR).findFirst().orElseThrow();
        assertThat(bad.message()).isEqualTo("Name may not be FAIL.");   // user-safe message preserved
    }

    @Test
    void run_unknownKey_is404() {
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx", null, sheet(List.of("* Name"),
                List.of(List.of("Alpha"))));
        assertThatThrownBy(() -> service.run("nope", file, ImportMode.VALIDATE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void template_generatesForKnownKey() {
        assertThat(service.template("test")).isNotEmpty();
    }

    /**
     * A file for the WRONG entity is rejected once, up front — not turned into one identical
     * "'Name' is required." error per row (a missing column reads back as an empty cell, so without
     * this check that is exactly what a 400-row mis-picked upload produces). The message names the
     * entity the file actually belongs to, because that is the operator's next click.
     */
    @Test
    void run_fileForAnotherEntity_rejectedUpFront_namingTheEntityItBelongsTo() {
        byte[] xlsx = sheet(List.of("* Product Code", "Qty"),
                List.of(List.of("SKU-1", "5"), List.of("SKU-2", "7")));
        MockMultipartFile file = new MockMultipartFile("file", "other-export.xlsx", null, xlsx);

        assertThatThrownBy(() -> service.run("test", file, ImportMode.VALIDATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't match \"Test\"")
                .hasMessageContaining("\"Name\"")
                .hasMessageContaining("It looks like a \"Other\" file");
    }

    /** Headers matching no registered entity: still one clear rejection, pointing at the template. */
    @Test
    void run_fileMatchingNoEntity_rejectedUpFront_pointingAtTheTemplate() {
        byte[] xlsx = sheet(List.of("Wibble"), List.of(List.of("x")));
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx", null, xlsx);

        assertThatThrownBy(() -> service.run("test", file, ImportMode.VALIDATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Download the \"Test\" template");
    }

    /**
     * Excel round-trips are careless with case/space — the header check must not be, AND the cell
     * lookup must agree with it: accepting the file and then failing every row on the same column
     * would be worse than rejecting it.
     */
    @Test
    void run_headerCaseAndSpacingDiffer_fileAcceptedAndRowsRead() {
        byte[] xlsx = sheet(List.of("  name  "), List.of(List.of("Alpha")));
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx", null, xlsx);

        ImportReport report = service.run("test", file, ImportMode.VALIDATE);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.errors()).isZero();
    }

    // --- self-contained fake handlers ---------------------------------------------------------------

    private static final class FakeHandler implements BulkImportHandler {
        @Override public String key() {
            return "test";
        }

        @Override public String displayName() {
            return "Test";
        }

        @Override public String permissionCode() {
            return "TEST.IMPORT";
        }

        @Override public List<ColumnSpec> columns(Long companyId) {
            return List.of(ColumnSpec.of("Name", true, "the name"));
        }

        @Override public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
            String name = ImportParsers.requireText(row, "Name");
            if ("FAIL".equals(name)) {
                throw new IllegalArgumentException("Name may not be FAIL.");
            }
            if ("SKIP".equals(name)) {
                return RowOutcome.skip(row.rowNumber(), name, "nothing to do");
            }
            return RowOutcome.create(row.rowNumber(), name);
        }
    }

    /** A second registered entity — so a file can belong to one entity while another is picked. */
    private static final class OtherHandler implements BulkImportHandler {
        @Override public String key() {
            return "other";
        }

        @Override public String displayName() {
            return "Other";
        }

        @Override public String permissionCode() {
            return "OTHER.IMPORT";
        }

        @Override public List<ColumnSpec> columns(Long companyId) {
            return List.of(ColumnSpec.of("Product Code", true, "the product"),
                    ColumnSpec.number("Qty", false, "how many"));
        }

        @Override public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
            return RowOutcome.create(row.rowNumber(), ImportParsers.requireText(row, "Product Code"));
        }
    }

    /** No-op transaction manager: begins a dummy transaction, does nothing on commit/rollback. */
    private static final class NoopTxManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override public void commit(TransactionStatus status) {
            // no-op
        }

        @Override public void rollback(TransactionStatus status) {
            // no-op
        }
    }

    private static byte[] sheet(List<String> headers, List<List<String>> dataRows) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Data");
            Row h = s.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                h.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < dataRows.size(); r++) {
                Row row = s.createRow(r + 1);
                List<String> cells = dataRows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(cells.get(c));
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
