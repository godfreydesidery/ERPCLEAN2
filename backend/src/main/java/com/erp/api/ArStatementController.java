package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.fmtAmtOrBlank;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.ar.domain.dto.ArAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.domain.dto.ArCustomerAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerRowDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.ar.domain.enums.ArLedgerEntryType;
import com.erp.modules.ar.service.ArAgeingQuery;
import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.ar.service.ArCustomerLedgerQuery;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR customer statement, ageing, and balance read endpoints (ADR-0014, FR-AR-08/12/19).
 * All computed on demand — not stored. companyId and customerId are Long ids.
 * Permission codes seeded in V11__accounts_receivable.sql: AR.STATEMENT.VIEW, AR.VIEW.
 *
 * <p>The customer may also be named by {@code customerUid} — what the web screen sends. It is
 * resolved inside {@code companyId} (a uid from another company is "not found"); the original
 * {@code customerId} form is unchanged.
 */
@RestController
@RequestMapping("/api/v1/ar")
public class ArStatementController {

    private final ArAgeingQuery         ageingQuery;
    private final ArBalanceService      balanceService;
    private final ArCustomerLedgerQuery ledgerQuery;
    private final TabularExporter       exporter;
    private final ExportLetterhead      letterhead;

    public ArStatementController(ArAgeingQuery ageingQuery, ArBalanceService balanceService,
                                 ArCustomerLedgerQuery ledgerQuery, TabularExporter exporter,
                                 ExportLetterhead letterhead) {
        this.ageingQuery    = ageingQuery;
        this.balanceService = balanceService;
        this.ledgerQuery    = ledgerQuery;
        this.exporter       = exporter;
        this.letterhead     = letterhead;
    }

    /**
     * Full customer statement (open items + ageing + recent receipts) as at a given date.
     * Defaults to today when {@code asAt} is omitted.
     */
    @GetMapping("/statement")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW')")
    public ArStatementDto statement(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerUid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.statement(companyId, customerIdOf(companyId, customerId, customerUid),
                asAt != null ? asAt : LocalDate.now());
    }

    /**
     * The customer statement as a printable document: company letterhead, the customer, the period,
     * balance brought forward, every invoice / receipt / credit note / write-off in the period with
     * a running balance, and the closing balance.
     *
     * <p>{@code toDate} defaults to {@code asAt}, then today; {@code fromDate} omitted runs the
     * statement from the customer's first transaction. Gated on the on-screen code AND
     * {@code REPORT.EXPORT}: a download leaves the system.
     */
    @GetMapping("/statement/export")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStatement(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerUid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        // No currency named: one section per currency the customer trades in (their own first).
        List<ArCustomerLedgerDto> sections = ledgerQuery.statements(companyId, customerId,
                customerUid, fromDate, toDate != null ? toDate : asAt, currency);
        ExportLetterhead.Letterhead head = letterhead.forCompany(companyId);
        return ExportLetterhead.download(exporter.export(
                flattenStatement(sections, head, ZonedDateTime.now()), format));
    }

    /**
     * Ageing breakdown by bucket (CURRENT / 1-30 / 31-60 / 61-90 / 90+) as at a given date.
     * When {@code customerId} is omitted the report covers ALL customers for the company
     * (company-wide ageing — required by the AR Ageing screen, bug #5 fix).
     */
    @GetMapping("/ageing")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW')")
    public List<ArAgeingRowDto> ageing(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.ageing(companyId, customerId,
                asAt != null ? asAt : LocalDate.now());
    }

    /**
     * Per-customer ageing breakdown (CFO credit-limit view) as at a given date — one row per
     * customer with open items, each carrying all five bucket amounts + total. Distinct from
     * {@link #ageing} which returns a 5-row company-wide bucket summary.
     */
    @GetMapping("/ageing/by-customer")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW')")
    public List<ArCustomerAgeingRowDto> ageingByCustomer(
            @RequestParam Long companyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.customerAgeing(companyId, asAt != null ? asAt : LocalDate.now());
    }

    /**
     * The per-customer ageing as a printable / spreadsheet document: one row per customer with its
     * five buckets and total, and a totals row. Same gate as the screen plus {@code REPORT.EXPORT}.
     */
    @GetMapping("/ageing/by-customer/export")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportAgeingByCustomer(
            @RequestParam Long companyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        LocalDate at = asAt != null ? asAt : LocalDate.now();
        // The read runs (and passes its tenant check) BEFORE the letterhead is loaded.
        List<ArCustomerAgeingRowDto> rows = ageingQuery.customerAgeing(companyId, at);
        ExportLetterhead.Letterhead head = letterhead.forCompany(companyId);
        return ExportLetterhead.download(exporter.export(
                flattenAgeing(rows, at, head, ZonedDateTime.now()), format));
    }

    /**
     * Current AR balance (outstanding − unallocated) for a customer.
     * Used by the Sales module at invoice finalise for the credit-limit check (FR-AR-19).
     */
    @GetMapping("/balance")
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ArBalanceDto balance(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerUid) {
        return balanceService.currentBalance(companyId,
                customerIdOf(companyId, customerId, customerUid));
    }

    // -------------------------------------------------------------------------

    /**
     * The id form passes straight through, exactly as before; the uid form is resolved inside the
     * company (and the company's tenant check runs first).
     */
    private Long customerIdOf(Long companyId, Long customerId, String customerUid) {
        if (customerUid == null || customerUid.isBlank()) {
            if (customerId == null) {
                throw new IllegalArgumentException("Choose a customer.");
            }
            return customerId;
        }
        return ledgerQuery.resolveCustomer(companyId, null, customerUid).id();
    }

    static TabularRenderModel flattenStatement(ArCustomerLedgerDto dto,
                                               ExportLetterhead.Letterhead head,
                                               ZonedDateTime now) {
        return flattenStatement(List.of(dto), head, now);
    }

    /** The customer statement document: one section per currency (one section = the classic layout). */
    static TabularRenderModel flattenStatement(List<ArCustomerLedgerDto> sections,
                                               ExportLetterhead.Letterhead head,
                                               ZonedDateTime now) {
        ArCustomerLedgerDto dto = sections.get(0);
        ReportCompanyHeaderDto company = head != null && head.company() != null
                ? head.company() : dto.company();
        List<String> partyLines = new ArrayList<>();
        partyLines.add("Customer: " + party(dto.customerCode(), dto.customerName()));
        if (dto.customerTin() != null && !dto.customerTin().isBlank()) {
            partyLines.add("Customer TIN: " + dto.customerTin());
        }
        if (dto.customerVrn() != null && !dto.customerVrn().isBlank()) {
            partyLines.add("Customer VRN: " + dto.customerVrn());
        }
        List<LedgerSection> printable = new ArrayList<>(sections.size());
        for (ArCustomerLedgerDto s : sections) {
            List<List<String>> rows = new ArrayList<>(s.rows().size());
            for (ArCustomerLedgerRowDto r : s.rows()) {
                rows.add(List.of(
                        r.date() != null ? r.date().toString() : "",
                        typeLabel(r.type()),
                        nullToEmpty(r.reference()),
                        nullToEmpty(r.description()),
                        fmtAmtOrBlank(r.debit()),
                        fmtAmtOrBlank(r.credit()),
                        fmtAmt(r.balance())));
            }
            printable.add(new LedgerSection(s.currency(), s.openingBalance(), rows,
                    s.totalDebit(), s.totalCredit(), s.closingBalance(), s.otherCurrencyCount()));
        }
        return ledgerDocument("Customer Statement", company, partyLines, dto.fromDate(),
                dto.toDate(), printable, "Amount due from customer", "Customer in credit",
                head, now);
    }

    /**
     * One currency of a party statement, its movement rows already formatted.
     *
     * @param otherCurrencyCount movements in currencies NOT printed anywhere in this document
     *                           (only meaningful when the caller named a single currency)
     */
    record LedgerSection(String currency, BigDecimal opening, List<List<String>> movementRows,
                         BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal closing,
                         int otherCurrencyCount) {}

    /**
     * The shared customer/supplier statement layout. One section prints exactly as the statement
     * always did (currency in the header, a closing totals row). Several sections — a party that
     * trades in more than one currency — print one block per currency, each with its own balance
     * brought forward and closing line, and no grand total: amounts in different currencies are
     * never added together.
     */
    static TabularRenderModel ledgerDocument(String title, ReportCompanyHeaderDto company,
                                             List<String> partyLines, LocalDate fromDate,
                                             LocalDate toDate, List<LedgerSection> sections,
                                             String positiveLabel, String negativeLabel,
                                             ExportLetterhead.Letterhead head, ZonedDateTime now) {
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.addAll(partyLines);
        headerLines.add(period(fromDate, toDate));
        boolean multi = sections.size() > 1;
        if (multi) {
            headerLines.add("Currencies: " + String.join(", ",
                    sections.stream().map(LedgerSection::currency).toList())
                    + " — one section per currency; amounts in different currencies are never"
                    + " added together.");
        } else {
            headerLines.add("Currency: " + sections.get(0).currency());
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> totalsRow = null;
        boolean anyMovement = false;
        for (LedgerSection s : sections) {
            if (multi) {
                rows.add(List.of("", "", "", "Currency: " + s.currency(), "", "", ""));
            }
            if (fromDate != null) {
                rows.add(List.of(fromDate.toString(), "", "", "Balance brought forward",
                        "", "", fmtAmt(s.opening())));
            }
            rows.addAll(s.movementRows());
            anyMovement |= !s.movementRows().isEmpty();
            List<String> closing = List.of("", "", "",
                    multi ? "Closing balance " + s.currency() : "Closing balance",
                    fmtAmt(s.totalDebit()), fmtAmt(s.totalCredit()), fmtAmt(s.closing()));
            if (multi) {
                rows.add(closing);
            } else {
                totalsRow = closing;
            }
        }

        List<String> footer = new ArrayList<>();
        if (!anyMovement) {
            footer.add("No transactions in this period.");
        }
        for (LedgerSection s : sections) {
            footer.add(closingSentence(s.closing(), s.currency(), positiveLabel, negativeLabel));
        }
        if (!multi && sections.get(0).otherCurrencyCount() > 0) {
            LedgerSection s = sections.get(0);
            footer.add("Note: " + s.otherCurrencyCount() + " transaction"
                    + (s.otherCurrencyCount() == 1 ? " is" : "s are")
                    + " in another currency and not included in this " + s.currency()
                    + " statement.");
        }
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel(title, headerLines,
                ExportLetterhead.generatedAt(now), ledgerColumns(), rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    static TabularRenderModel flattenAgeing(List<ArCustomerAgeingRowDto> ageing, LocalDate asAt,
                                            ExportLetterhead.Letterhead head, ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Ageing as at " + asAt);

        // Rows arrive one per customer PER CURRENCY. One currency: the document is exactly as it
        // always was (currency in the header, one totals row). More than one: a Currency column,
        // and one total per currency, because amounts in different currencies cannot be added.
        List<String> currencies = ageing.stream()
                .map(ArCustomerAgeingRowDto::currency).distinct().toList();
        boolean multiCurrency = currencies.size() > 1;
        if (!multiCurrency && !currencies.isEmpty() && currencies.get(0) != null) {
            headerLines.add("Currency: " + currencies.get(0));
        }
        if (multiCurrency) {
            headerLines.add("Amounts are in each invoice's own currency; totals are per currency.");
        }

        List<Column> columns = new ArrayList<>(List.of(
                new Column("Code", Align.LEFT),
                new Column("Customer", Align.LEFT)));
        if (multiCurrency) {
            columns.add(new Column("Currency", Align.LEFT));
        }
        columns.addAll(List.of(
                new Column("Current", Align.RIGHT),
                new Column("1-30 days", Align.RIGHT),
                new Column("31-60 days", Align.RIGHT),
                new Column("61-90 days", Align.RIGHT),
                new Column("Over 90 days", Align.RIGHT),
                new Column("Total", Align.RIGHT)));

        Map<String, BigDecimal[]> sumsByCurrency = new LinkedHashMap<>();
        Map<String, Integer> countByCurrency = new LinkedHashMap<>();
        List<List<String>> rows = new ArrayList<>(ageing.size() + currencies.size());
        for (ArCustomerAgeingRowDto r : ageing) {
            BigDecimal[] cells = {r.current(), r.days1to30(), r.days31to60(),
                    r.days61to90(), r.days91Plus(), r.total()};
            BigDecimal[] sums = sumsByCurrency.computeIfAbsent(nullToEmpty(r.currency()),
                    c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            countByCurrency.merge(nullToEmpty(r.currency()), 1, Integer::sum);
            List<String> row = new ArrayList<>(columns.size());
            row.add(nullToEmpty(r.customerCode()));
            row.add(nullToEmpty(r.customerName()));
            if (multiCurrency) {
                row.add(nullToEmpty(r.currency()));
            }
            for (int i = 0; i < cells.length; i++) {
                BigDecimal v = cells[i] != null ? cells[i] : BigDecimal.ZERO;
                sums[i] = sums[i].add(v);
                row.add(fmtAmt(v));
            }
            rows.add(row);
        }

        List<String> totalsRow;
        if (!multiCurrency) {
            BigDecimal[] sums = sumsByCurrency.isEmpty()
                    ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}
                    : sumsByCurrency.values().iterator().next();
            totalsRow = new ArrayList<>(8);
            totalsRow.add("");
            totalsRow.add("TOTAL (" + ageing.size() + " customer" + (ageing.size() == 1 ? ")" : "s)"));
            for (BigDecimal s : sums) {
                totalsRow.add(fmtAmt(s));
            }
        } else {
            // One total line per currency, in the table body; no single grand total exists.
            totalsRow = null;
            for (Map.Entry<String, BigDecimal[]> e : sumsByCurrency.entrySet()) {
                int n = countByCurrency.getOrDefault(e.getKey(), 0);
                List<String> line = new ArrayList<>(columns.size());
                line.add("");
                line.add("TOTAL " + e.getKey() + " (" + n + " customer" + (n == 1 ? ")" : "s)"));
                line.add(e.getKey());
                for (BigDecimal s : e.getValue()) {
                    line.add(fmtAmt(s));
                }
                rows.add(line);
            }
        }

        List<String> footer = new ArrayList<>();
        if (ageing.isEmpty()) {
            footer.add("No customer has an open balance.");
        }
        footer.add("Buckets count days past each invoice's due date.");
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel("AR Ageing by Customer", headerLines,
                ExportLetterhead.generatedAt(now), columns, rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    static List<Column> ledgerColumns() {
        return List.of(
                new Column("Date", Align.LEFT),
                new Column("Type", Align.LEFT),
                new Column("Reference", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Debit", Align.RIGHT),
                new Column("Credit", Align.RIGHT),
                new Column("Balance", Align.RIGHT));
    }

    static String period(LocalDate from, LocalDate to) {
        return from != null
                ? "Period: " + from + " to " + to
                : "Period: all transactions up to " + to;
    }

    static String party(String code, String name) {
        String c = nullToEmpty(code);
        String n = nullToEmpty(name);
        if (c.isEmpty()) {
            return n;
        }
        return n.isEmpty() ? c : c + " — " + n;
    }

    /** "Amount due from customer: TZS 1,250.00" / "Customer in credit: TZS 300.00" / nothing due. */
    static String closingSentence(BigDecimal closing, String currency,
                                  String positiveLabel, String negativeLabel) {
        BigDecimal c = closing != null ? closing : BigDecimal.ZERO;
        if (c.signum() == 0) {
            return "Nothing outstanding at the end of the period.";
        }
        return (c.signum() > 0 ? positiveLabel : negativeLabel) + ": " + currency + " "
                + fmtAmt(c.abs());
    }

    private static String typeLabel(ArLedgerEntryType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case OPENING_BALANCE  -> "Opening balance";
            case INVOICE          -> "Invoice";
            case RECEIPT          -> "Receipt";
            case RECEIPT_REVERSAL -> "Reversal";
            case CREDIT_NOTE      -> "Credit note";
            case WRITE_OFF        -> "Write-off";
        };
    }
}
