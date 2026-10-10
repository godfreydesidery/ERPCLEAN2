package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.fmtAmtOrBlank;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.cashbank.domain.dto.CashAccountStatementDto;
import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashGlReconciliationDto;
import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.service.CashAccountStatementQuery;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.cashbank.service.CashGlReconciliationQuery;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only cash account balance, statement, and GL reconciliation views
 * (ADR-0016 D-9, FR-CASH-12/17).
 * Permission: CASH.VIEW (the statement export additionally REPORT.EXPORT).
 */
@RestController
@RequestMapping("/api/v1/cash/statements")
public class CashAccountStatementController {

    private final CashAccountStatementQuery statementQuery;
    private final CashGlReconciliationQuery glReconciliationQuery;
    private final CashBankAccountService    accountService;
    private final TabularExporter           exporter;
    private final ExportLetterhead          letterhead;

    public CashAccountStatementController(CashAccountStatementQuery statementQuery,
                                           CashGlReconciliationQuery glReconciliationQuery,
                                           CashBankAccountService accountService,
                                           TabularExporter exporter,
                                           ExportLetterhead letterhead) {
        this.statementQuery        = statementQuery;
        this.glReconciliationQuery = glReconciliationQuery;
        this.accountService        = accountService;
        this.exporter              = exporter;
        this.letterhead            = letterhead;
    }

    /** Current book balance for a single account (FR-CASH-12). */
    @GetMapping("/accounts/uid/{uid}/balance")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashAccountBalanceDto getBalance(@PathVariable String uid) {
        return statementQuery.getBalance(uid);
    }

    /** Full transaction statement for a single account (FR-CASH-12). */
    @GetMapping("/accounts/uid/{uid}/statement")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashAccountStatementDto getStatement(@PathVariable String uid) {
        return statementQuery.getStatement(uid);
    }

    /**
     * The account statement as a document: letterhead, the account (code, bank, account number),
     * the period, balance brought forward, every money-in / money-out line with a running balance,
     * and the closing balance. Both dates are optional — omitted, the statement runs from the
     * account's first transaction to its last. Same gate as the screen plus {@code REPORT.EXPORT}.
     */
    @GetMapping("/accounts/uid/{uid}/statement/export")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStatement(
            @PathVariable String uid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("The start date must be on or before the end date.");
        }
        // Both reads run the account's own tenant check before the letterhead is loaded.
        CashAccountStatementDto statement = statementQuery.getStatement(uid);
        CashBankAccountDto account = accountService.getByUid(uid);
        ExportLetterhead.Letterhead head = letterhead.forCompany(account.companyId());
        return ExportLetterhead.download(exporter.export(
                flattenStatement(statement, account, fromDate, toDate, head, letterhead.now(account.companyId())),
                format));
    }

    /** Book balances for all accounts of a company (FR-CASH-12). */
    @GetMapping("/balances")
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashAccountBalanceDto> listBalances(@RequestParam Long companyId) {
        return statementQuery.listBalances(companyId);
    }

    /** Book vs GL balance for all accounts of a company (ADR-0016 D-9, FR-CASH-17). */
    @GetMapping("/gl-reconciliation")
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashGlReconciliationDto> glReconciliation(@RequestParam Long companyId) {
        return glReconciliationQuery.reconcileAll(companyId);
    }

    /** Book vs GL balance for a single account (FR-CASH-17). */
    @GetMapping("/accounts/uid/{uid}/gl-reconciliation")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashGlReconciliationDto glReconciliationOne(@PathVariable String uid) {
        return glReconciliationQuery.reconcileOne(uid);
    }

    // -------------------------------------------------------------------------

    /**
     * Lays the account's transactions out for one period. The statement read returns the whole
     * history in date order; the period is cut here: everything before {@code fromDate} folds into
     * the balance brought forward, everything after {@code toDate} is left off.
     */
    static TabularRenderModel flattenStatement(CashAccountStatementDto statement,
                                               CashBankAccountDto account,
                                               LocalDate fromDate, LocalDate toDate,
                                               ExportLetterhead.Letterhead head,
                                               ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Account: " + ArStatementController.party(
                account != null ? account.code() : null, statement.accountName()));
        if (account != null && account.bankName() != null && !account.bankName().isBlank()) {
            StringBuilder bank = new StringBuilder("Bank: ").append(account.bankName());
            if (account.bankBranch() != null && !account.bankBranch().isBlank()) {
                bank.append(", ").append(account.bankBranch());
            }
            if (account.bankAccountNo() != null && !account.bankAccountNo().isBlank()) {
                bank.append("    Account No: ").append(account.bankAccountNo());
            }
            headerLines.add(bank.toString());
        }
        List<CashTransactionDto> all = statement.transactions() != null
                ? statement.transactions() : List.of();
        LocalDate lastDate = all.isEmpty() ? null : all.get(all.size() - 1).txnDate();
        LocalDate shownTo = toDate != null ? toDate
                : (lastDate != null ? lastDate : now.toLocalDate());
        headerLines.add(ArStatementController.period(fromDate, shownTo));
        headerLines.add("Currency: " + nullToEmpty(statement.currency()));

        List<Column> columns = List.of(
                new Column("Date", Align.LEFT),
                new Column("Txn No", Align.LEFT),
                new Column("Type", Align.LEFT),
                new Column("Reference", Align.LEFT),
                new Column("Memo", Align.LEFT),
                new Column("Money In", Align.RIGHT),
                new Column("Money Out", Align.RIGHT),
                new Column("Balance", Align.RIGHT));

        BigDecimal opening = BigDecimal.ZERO;
        List<CashTransactionDto> inPeriod = new ArrayList<>();
        for (CashTransactionDto t : all) {
            LocalDate d = t.txnDate();
            if (fromDate != null && d != null && d.isBefore(fromDate)) {
                opening = opening.add(signed(t));
            } else if (toDate == null || d == null || !d.isAfter(toDate)) {
                inPeriod.add(t);
            }
        }

        List<List<String>> rows = new ArrayList<>(inPeriod.size() + 1);
        if (fromDate != null) {
            rows.add(List.of(fromDate.toString(), "", "", "", "Balance brought forward",
                    "", "", fmtAmt(opening)));
        }
        BigDecimal running  = opening;
        BigDecimal totalIn  = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        for (CashTransactionDto t : inPeriod) {
            BigDecimal amt = t.amount() != null ? t.amount() : BigDecimal.ZERO;
            boolean in = t.direction() == CashTxnDirection.IN;
            running = running.add(signed(t));
            if (in) {
                totalIn = totalIn.add(amt);
            } else {
                totalOut = totalOut.add(amt);
            }
            rows.add(List.of(
                    t.txnDate() != null ? t.txnDate().toString() : "",
                    nullToEmpty(t.txnNumber()),
                    t.txnType() != null ? typeLabel(t.txnType().name()) : "",
                    nullToEmpty(t.sourceRef()),
                    nullToEmpty(t.memo()),
                    in ? fmtAmtOrBlank(amt) : "",
                    in ? "" : fmtAmtOrBlank(amt),
                    fmtAmt(running)));
        }
        List<String> totalsRow = List.of("", "", "", "", "Closing balance",
                fmtAmt(totalIn), fmtAmt(totalOut), fmtAmt(running));

        List<String> footer = new ArrayList<>();
        if (inPeriod.isEmpty()) {
            footer.add("No transactions in this period.");
        }
        if (toDate != null && toDate.isBefore(now.toLocalDate())) {
            // A past period: the closing balance above is as at toDate, which is not today's figure.
            footer.add("Current book balance today: " + nullToEmpty(statement.currency()) + " "
                    + fmtAmt(statement.currentBalance()));
        }
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel("Cash Account Statement", headerLines,
                ExportLetterhead.generatedAt(now), columns, rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    private static BigDecimal signed(CashTransactionDto t) {
        BigDecimal amt = t.amount() != null ? t.amount() : BigDecimal.ZERO;
        return t.direction() == CashTxnDirection.IN ? amt : amt.negate();
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "AR_RECEIPT"   -> "Customer receipt";
            case "AP_PAYMENT"   -> "Supplier payment";
            case "TRANSFER_IN"  -> "Transfer in";
            case "TRANSFER_OUT" -> "Transfer out";
            case "DIRECT_ENTRY" -> "Direct entry";
            default             -> type;
        };
    }
}
