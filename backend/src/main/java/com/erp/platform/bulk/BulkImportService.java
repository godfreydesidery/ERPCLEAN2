package com.erp.platform.bulk;

import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates bulk master-data operations (mass create/update from a spreadsheet): builds the
 * per-entity template, parses an upload, and runs each row through the entity's
 * {@link BulkImportHandler}.
 *
 * <p><b>Validate/commit parity.</b> Both modes execute the SAME handler code (which calls the real
 * module create/update service, so ALL business rules run — not just structural checks). The only
 * difference is the transaction outcome: a COMMIT row runs in the handler's own transaction and
 * commits; a VALIDATE row runs inside a transaction this service always rolls back (via a sentinel),
 * so nothing is persisted but a "0 errors" preview genuinely means the commit will succeed.
 *
 * <p>Each row is its own transaction, so a bad row fails alone and never rolls back the others. The
 * active company comes from {@link RequestContext} — an import always targets the caller's own tenant.
 */
@Service
public class BulkImportService {

    private final Map<String, BulkImportHandler> handlers = new LinkedHashMap<>();
    private final XlsxTemplateWriter templateWriter;
    private final XlsxRowReader rowReader;
    private final TransactionTemplate validateTx;

    public BulkImportService(List<BulkImportHandler> handlerList,
                             XlsxTemplateWriter templateWriter,
                             XlsxRowReader rowReader,
                             PlatformTransactionManager transactionManager) {
        handlerList.stream()
                .sorted(Comparator.comparing(BulkImportHandler::displayName))
                .forEach(h -> handlers.put(h.key(), h));
        this.templateWriter = templateWriter;
        this.rowReader = rowReader;
        this.validateTx = new TransactionTemplate(transactionManager);
    }

    /** The importable entities (key + label + permission) for the picker. */
    public List<EntityDescriptor> entities() {
        List<EntityDescriptor> list = new ArrayList<>();
        handlers.values().forEach(h ->
                list.add(new EntityDescriptor(h.key(), h.displayName(), h.permissionCode())));
        return list;
    }

    public BulkImportHandler handler(String key) {
        BulkImportHandler h = handlers.get(key);
        if (h == null) {
            throw new NotFoundException("Unknown import type.");
        }
        return h;
    }

    /** Build the blank XLSX template for {@code key}, scoped to the active company. */
    public byte[] template(String key) {
        BulkImportHandler h = handler(key);
        Long companyId = activeCompanyId();
        return templateWriter.write(h.displayName(), h.columns(companyId));
    }

    /**
     * Build an XLSX pre-filled with {@code key}'s current rows (the download → edit → re-upload
     * round-trip). {@code params} carries optional query parameters to the handler (e.g. a price list).
     */
    public byte[] export(String key, Map<String, String> params) {
        BulkImportHandler h = handler(key);
        Long companyId = activeCompanyId();
        return templateWriter.write(h.displayName(), h.columns(companyId), h.exportRows(companyId, params));
    }

    /** Parse the upload and run every row in {@code mode}, returning the aggregate report. */
    public ImportReport run(String key, MultipartFile file, ImportMode mode) {
        BulkImportHandler h = handler(key);
        Long companyId = activeCompanyId();
        XlsxRowReader.ParsedSheet sheet = parse(file);
        assertSheetMatchesEntity(h, companyId, sheet.headers());
        List<ImportRow> rows = sheet.rows();
        ImportContext ctx = new ImportContext();
        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        for (ImportRow row : rows) {
            outcomes.add(safeProcess(h, companyId, row, mode, ctx));
        }
        return ImportReport.from(key, mode, outcomes);
    }

    /**
     * Reject a file whose columns don't belong to the picked entity, ONCE and up front — before any
     * row runs.
     *
     * <p>Without this, a sheet for the wrong entity isn't recognised as such: a missing column reads
     * back as an empty cell ({@link ImportRow#get}), so every row fails its first required field and
     * the operator gets N identical "'Party Type' is required." errors with no hint that the real
     * mistake was leaving the entity picker on its default. The wizard defaults to the first entity
     * alphabetically, so uploading an export straight after a page reload lands exactly here.
     *
     * <p>When the headers match a DIFFERENT registered entity, the message names it — the operator's
     * next click is then obvious.
     */
    private void assertSheetMatchesEntity(BulkImportHandler h, Long companyId, List<String> headers) {
        Set<String> present = headers.stream()
                .filter(s -> !s.isBlank())
                .map(BulkImportService::normalise)
                .collect(Collectors.toSet());

        List<String> missing = requiredHeaders(h, companyId).stream()
                .filter(header -> !present.contains(normalise(header)))
                .toList();
        if (missing.isEmpty()) {
            return;
        }

        String looksLike = handlers.values().stream()
                .filter(other -> !other.key().equals(h.key()))
                .filter(other -> {
                    List<String> req = requiredHeaders(other, companyId);
                    return !req.isEmpty()
                            && req.stream().allMatch(header -> present.contains(normalise(header)));
                })
                .map(BulkImportHandler::displayName)
                .findFirst()
                .orElse(null);

        String lead = "This file doesn't match \"" + h.displayName() + "\" — it has no "
                + quotedList(missing) + " column" + (missing.size() > 1 ? "s" : "") + ".";
        throw new IllegalArgumentException(looksLike != null
                ? lead + " It looks like a \"" + looksLike + "\" file — choose that in step 1 and "
                       + "upload it again."
                : lead + " Download the \"" + h.displayName() + "\" template in step 2 and fill "
                       + "that in.");
    }

    /** The headers a handler cannot import without. Reference columns are read-only, never required. */
    private static List<String> requiredHeaders(BulkImportHandler h, Long companyId) {
        return h.columns(companyId).stream()
                .filter(ColumnSpec::required)
                .map(ColumnSpec::header)
                .toList();
    }

    /** Header matching ignores case and surrounding space — Excel round-trips are not that careful. */
    private static String normalise(String header) {
        return header.trim().toLowerCase(Locale.ROOT);
    }

    private static String quotedList(List<String> headers) {
        return headers.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", "));
    }

    private RowOutcome safeProcess(BulkImportHandler h, Long companyId, ImportRow row,
                                   ImportMode mode, ImportContext ctx) {
        try {
            if (mode == ImportMode.COMMIT) {
                return h.process(companyId, row, mode, ctx);
            }
            // VALIDATE: execute the real create/update inside a transaction we always roll back — so
            // every business rule runs but nothing is persisted. The outcome rides out on a sentinel
            // exception, which is also what forces the rollback.
            try {
                validateTx.executeWithoutResult(status ->
                        throwOutcome(h.process(companyId, row, ImportMode.VALIDATE, ctx)));
                return null; // unreachable — the callback always throws
            } catch (ValidationComplete vc) {
                return vc.outcome;
            }
        } catch (RuntimeException e) {
            return RowOutcome.error(row.rowNumber(), userMessage(e));
        }
    }

    private static void throwOutcome(RowOutcome outcome) {
        throw new ValidationComplete(outcome);
    }

    private XlsxRowReader.ParsedSheet parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        try (InputStream in = file.getInputStream()) {
            return rowReader.readSheet(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("The file could not be read.");
        }
    }

    private Long activeCompanyId() {
        RequestContext.Principal p = RequestContext.get();
        if (p == null || p.companyId() == null) {
            throw new IllegalStateException("No active company in context. Select a company and retry.");
        }
        return p.companyId();
    }

    /**
     * Keep per-row messages user-safe (error-message hygiene): surface the message only for the
     * known, message-safe exception types the handlers/services raise; anything unexpected gets a
     * generic message so internal detail never reaches the report.
     */
    private static String userMessage(RuntimeException e) {
        if (e instanceof IllegalArgumentException
                || e instanceof NotFoundException
                || e instanceof ConflictException) {
            String m = e.getMessage();
            if (m != null && !m.isBlank()) {
                return m;
            }
        }
        return "The row could not be processed. Check the values and try again.";
    }

    /** Control-flow signal: carries a validated row's outcome out of the rolled-back transaction. */
    private static final class ValidationComplete extends RuntimeException {
        final transient RowOutcome outcome;

        ValidationComplete(RowOutcome outcome) {
            super(null, null, false, false); // no message, no stack trace — this is control flow
            this.outcome = outcome;
        }
    }

    /** Lightweight descriptor for the entity picker. */
    public record EntityDescriptor(String key, String label, String permissionCode) {
    }
}
