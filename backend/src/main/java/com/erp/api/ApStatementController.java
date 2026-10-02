package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.fmtAmtOrBlank;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerRowDto;
import com.erp.modules.ap.domain.dto.ApSupplierRefDto;
import com.erp.modules.ap.domain.enums.ApLedgerEntryType;
import com.erp.modules.ap.service.ApAgeingQuery;
import com.erp.modules.ap.service.ApBalanceService;
import com.erp.modules.ap.service.ApReconciliationQuery;
import com.erp.modules.ap.service.ApSupplierLedgerQuery;
import com.erp.modules.ar.domain.enums.AgeingBucket;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AP reporting: balance, ageing, reconciliation, supplier statement (ADR-0015 D-7/D-8).
 * All read-only; permission: AP.VIEW (exports additionally REPORT.EXPORT).
 *
 * <p>The supplier may also be named by {@code supplierUid} — what the web screen sends. It is
 * resolved inside {@code companyId} (a uid from another company is "not found"); the original
 * {@code supplierId} form is unchanged.
 */
@RestController
@RequestMapping("/api/v1/ap/statement")
public class ApStatementController {

    private final ApBalanceService      balanceService;
    private final ApAgeingQuery         ageingQuery;
    private final ApReconciliationQuery reconciliationQuery;
    private final ApSupplierLedgerQuery ledgerQuery;
    private final TabularExporter       exporter;
    private final ExportLetterhead      letterhead;

    public ApStatementController(ApBalanceService balanceService,
                                  ApAgeingQuery ageingQuery,
                                  ApReconciliationQuery reconciliationQuery,
                                  ApSupplierLedgerQuery ledgerQuery,
                                  TabularExporter exporter,
                                  ExportLetterhead letterhead) {
        this.balanceService      = balanceService;
        this.ageingQuery         = ageingQuery;
        this.reconciliationQuery = reconciliationQuery;
        this.ledgerQuery         = ledgerQuery;
        this.exporter            = exporter;
        this.letterhead          = letterhead;
    }

    /** Current outstanding balance for a supplier. */
    @GetMapping("/balance")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApBalanceDto balance(@RequestParam Long companyId,
                                @RequestParam(required = false) Long supplierId,
                                @RequestParam(required = false) String supplierUid) {
        return balanceService.currentBalance(companyId,
                supplierIdOf(companyId, supplierId, supplierUid));
    }

    /** Ageing breakdown as at a given date (today when omitted). */
    @GetMapping("/ageing")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public List<ApAgeingRowDto> ageing(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.ageing(companyId, supplierIdOf(companyId, supplierId, supplierUid),
                asAt != null ? asAt : LocalDate.now());
    }

    /**
     * The supplier's ageing as a document: the five buckets and the total, under the letterhead.
     * Same gate as the screen plus {@code REPORT.EXPORT}.
     */
    @GetMapping("/ageing/export")
    @PreAuthorize("@perm.has('AP.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportAgeing(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        LocalDate at = asAt != null ? asAt : LocalDate.now();
        ApSupplierRefDto supplier = ledgerQuery.resolveSupplier(companyId, supplierId, supplierUid);
        List<ApAgeingRowDto> rows = ageingQuery.ageing(companyId, supplier.id(), at);
        ExportLetterhead.Letterhead head = letterhead.forCompany(companyId);
        return ExportLetterhead.download(exporter.export(
                flattenAgeing(rows, supplier, at, head, ZonedDateTime.now()), format));
    }

    /**
     * The supplier statement as a document: letterhead, the supplier, the period, balance brought
     * forward, every bill / payment / debit note in the period with a running balance, and the
     * closing balance (what we owe). {@code toDate} defaults to {@code asAt}, then today;
     * {@code fromDate} omitted runs from the supplier's first transaction.
     *
     * <p>The view sibling of this export is the screen's own read ({@code /balance} + the open
     * bills), gated {@code AP.VIEW}; the export requires that AND {@code REPORT.EXPORT}.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('AP.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStatement(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        ApSupplierLedgerDto dto = ledgerQuery.ledger(companyId, supplierId, supplierUid,
                fromDate, toDate != null ? toDate : asAt, currency);
        ExportLetterhead.Letterhead head = letterhead.forCompany(companyId);
        return ExportLetterhead.download(exporter.export(
                flattenStatement(dto, head, ZonedDateTime.now()), format));
    }

    /** Sub-ledger vs GL 2100 reconciliation. */
    @GetMapping("/reconciliation")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApReconciliationDto reconcile(@RequestParam Long companyId) {
        return reconciliationQuery.reconcile(companyId);
    }

    // -------------------------------------------------------------------------

    /** The id form passes straight through, exactly as before; the uid form is resolved in-company. */
    private Long supplierIdOf(Long companyId, Long supplierId, String supplierUid) {
        if (supplierUid == null || supplierUid.isBlank()) {
            if (supplierId == null) {
                throw new IllegalArgumentException("Choose a supplier.");
            }
            return supplierId;
        }
        return ledgerQuery.resolveSupplier(companyId, null, supplierUid).id();
    }

    static TabularRenderModel flattenStatement(ApSupplierLedgerDto dto,
                                               ExportLetterhead.Letterhead head,
                                               ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null && head.company() != null
                ? head.company() : dto.company();
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Supplier: " + ArStatementController.party(dto.supplierCode(), dto.supplierName()));
        if (dto.supplierTin() != null && !dto.supplierTin().isBlank()) {
            headerLines.add("Supplier TIN: " + dto.supplierTin());
        }
        if (dto.supplierVrn() != null && !dto.supplierVrn().isBlank()) {
            headerLines.add("Supplier VRN: " + dto.supplierVrn());
        }
        headerLines.add(ArStatementController.period(dto.fromDate(), dto.toDate()));
        headerLines.add("Currency: " + dto.currency());

        List<List<String>> rows = new ArrayList<>(dto.rows().size() + 1);
        if (dto.fromDate() != null) {
            rows.add(List.of(dto.fromDate().toString(), "", "", "Balance brought forward",
                    "", "", fmtAmt(dto.openingBalance())));
        }
        for (ApSupplierLedgerRowDto r : dto.rows()) {
            rows.add(List.of(
                    r.date() != null ? r.date().toString() : "",
                    typeLabel(r.type()),
                    nullToEmpty(r.reference()),
                    nullToEmpty(r.description()),
                    fmtAmtOrBlank(r.debit()),
                    fmtAmtOrBlank(r.credit()),
                    fmtAmt(r.balance())));
        }
        List<String> totalsRow = List.of("", "", "", "Closing balance",
                fmtAmt(dto.totalDebit()), fmtAmt(dto.totalCredit()), fmtAmt(dto.closingBalance()));

        List<String> footer = new ArrayList<>();
        if (dto.rows().isEmpty()) {
            footer.add("No transactions in this period.");
        }
        footer.add(ArStatementController.closingSentence(dto.closingBalance(), dto.currency(),
                "Amount owed to supplier", "Supplier owes us (advance / credit)"));
        if (dto.otherCurrencyCount() > 0) {
            footer.add("Note: " + dto.otherCurrencyCount() + " transaction"
                    + (dto.otherCurrencyCount() == 1 ? " is" : "s are")
                    + " in another currency and not included in this " + dto.currency()
                    + " statement.");
        }
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel("Supplier Statement", headerLines,
                ExportLetterhead.generatedAt(now), ArStatementController.ledgerColumns(),
                rows, totalsRow, footer, head != null ? head.logoDataUri() : null);
    }

    static TabularRenderModel flattenAgeing(List<ApAgeingRowDto> ageing, ApSupplierRefDto supplier,
                                            LocalDate asAt, ExportLetterhead.Letterhead head,
                                            ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Supplier: " + ArStatementController.party(supplier.code(), supplier.name()));
        headerLines.add("Ageing as at " + asAt);
        String currency = ageing.isEmpty() ? null : ageing.get(0).currency();
        if (currency != null) {
            headerLines.add("Currency: " + currency);
        }

        List<Column> columns = List.of(
                new Column("Age (days past due)", Align.LEFT),
                new Column("Amount", Align.RIGHT));
        List<List<String>> rows = new ArrayList<>(ageing.size());
        BigDecimal total = BigDecimal.ZERO;
        for (ApAgeingRowDto r : ageing) {
            BigDecimal v = r.amount() != null ? r.amount() : BigDecimal.ZERO;
            total = total.add(v);
            rows.add(List.of(bucketLabel(r.bucket()), fmtAmt(v)));
        }
        List<String> totalsRow = List.of("TOTAL OUTSTANDING", fmtAmt(total));

        List<String> footer = new ArrayList<>();
        footer.add("Open bills only; payments not yet allocated to a bill are not in these buckets.");
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel("Supplier Ageing", headerLines,
                ExportLetterhead.generatedAt(now), columns, rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    static String bucketLabel(AgeingBucket bucket) {
        if (bucket == null) {
            return "";
        }
        return switch (bucket) {
            case CURRENT  -> "Current (not yet due)";
            case D1_30    -> "1-30 days";
            case D31_60   -> "31-60 days";
            case D61_90   -> "61-90 days";
            case D90_PLUS -> "Over 90 days";
        };
    }

    private static String typeLabel(ApLedgerEntryType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case OPENING_BALANCE  -> "Opening balance";
            case BILL             -> "Bill";
            case PAYMENT          -> "Payment";
            case PAYMENT_REVERSAL -> "Reversal";
            case DEBIT_NOTE       -> "Debit note";
        };
    }
}
