package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts statement DTOs to {@link StatementRenderModel} for renderer consumption (ADR-0018 D-9).
 */
@Component
public class StatementModelFlattener {

    public StatementRenderModel flatten(IncomeStatementDto dto) {
        List<Row> rows = new ArrayList<>();
        for (StatementSectionDto sec : dto.sections()) {
            rows.add(Row.sectionHeader(sec.title()));
            for (StatementLineDto line : sec.lines()) {
                rows.add(Row.line(line.accountName(), line.amounts().current(), line.amounts().comparative()));
            }
            rows.add(Row.subtotal("Total " + sec.title(),
                    sec.subtotal().current(), sec.subtotal().comparative()));
        }
        rows.add(Row.subtotal("Gross Profit",
                dto.grossProfit().current(), dto.grossProfit().comparative()));
        rows.add(Row.total("Net Profit",
                dto.netProfit().current(), dto.netProfit().comparative()));
        rows.add(Row.reconciliation(dto.reconciliation().label(),
                dto.reconciliation().difference().current(), dto.reconciliation().ties()));

        return new StatementRenderModel("Income Statement",
                dto.header().companyName(), dto.header().currency(),
                dto.header().periodLabel(), dto.header().comparativeLabel(),
                Instant.now().toString(), rows);
    }

    public StatementRenderModel flatten(BalanceSheetDto dto) {
        List<Row> rows = new ArrayList<>();
        for (StatementSectionDto sec : dto.sections()) {
            rows.add(Row.sectionHeader(sec.title()));
            for (StatementLineDto line : sec.lines()) {
                rows.add(Row.line(line.accountName(), line.amounts().current(), line.amounts().comparative()));
            }
            rows.add(Row.subtotal("Total " + sec.title(),
                    sec.subtotal().current(), sec.subtotal().comparative()));
        }
        rows.add(Row.total("Total Assets",
                dto.totalAssets().current(), dto.totalAssets().comparative()));
        rows.add(Row.total("Total Liabilities",
                dto.totalLiabilities().current(), dto.totalLiabilities().comparative()));
        rows.add(Row.total("Total Equity",
                dto.totalEquity().current(), dto.totalEquity().comparative()));
        rows.add(Row.reconciliation(dto.reconciliation().label(),
                dto.reconciliation().difference().current(), dto.reconciliation().ties()));

        return new StatementRenderModel("Balance Sheet",
                dto.header().companyName(), dto.header().currency(),
                dto.header().periodLabel(), dto.header().comparativeLabel(),
                Instant.now().toString(), rows);
    }

    public StatementRenderModel flatten(CashFlowStatementDto dto) {
        List<Row> rows = new ArrayList<>();
        for (StatementSectionDto sec : dto.sections()) {
            rows.add(Row.sectionHeader(sec.title()));
            for (StatementLineDto line : sec.lines()) {
                rows.add(Row.line(line.accountName(), line.amounts().current(), line.amounts().comparative()));
            }
            rows.add(Row.subtotal("Net " + sec.title(),
                    sec.subtotal().current(), sec.subtotal().comparative()));
        }
        rows.add(Row.total("Net Change in Cash",
                dto.netChangeInCash().current(), dto.netChangeInCash().comparative()));
        rows.add(Row.line("Opening Cash and Bank",
                dto.openingCash().current(), dto.openingCash().comparative()));
        rows.add(Row.line("Closing Cash and Bank",
                dto.closingCash().current(), dto.closingCash().comparative()));
        rows.add(Row.reconciliation(dto.reconciliation().label(),
                dto.reconciliation().difference().current(), dto.reconciliation().ties()));

        return new StatementRenderModel("Cash Flow Statement",
                dto.header().companyName(), dto.header().currency(),
                dto.header().periodLabel(), dto.header().comparativeLabel(),
                Instant.now().toString(), rows);
    }

    public StatementRenderModel flatten(AccountLedgerDto dto) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.line("Opening Balance", dto.openingBalance(), BigDecimal.ZERO));
        for (var row : dto.rows()) {
            rows.add(Row.line(
                    row.postingDate() + " " + nullStr(row.sourceType()) + " " + nullStr(row.sourceRef()),
                    row.debit().subtract(row.credit()),
                    BigDecimal.ZERO));
        }
        rows.add(Row.total("Closing Balance", dto.closingBalance(), BigDecimal.ZERO));

        return new StatementRenderModel("Account Ledger — " + dto.accountName(),
                dto.header().companyName(), dto.header().currency(),
                dto.header().periodLabel(), null,
                Instant.now().toString(), rows);
    }

    private String nullStr(String s) { return s != null ? s : ""; }
}
