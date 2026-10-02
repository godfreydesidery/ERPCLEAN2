package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.dto.ChangesInEquityDto;
import com.erp.modules.reporting.domain.dto.EquityMovementRowDto;
import com.erp.modules.reporting.domain.dto.FinancialRatioDto;
import com.erp.modules.reporting.domain.dto.FinancialRatiosDto;
import com.erp.modules.reporting.domain.dto.RatioInputDto;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Flattens the Statement of Changes in Equity and the Financial Ratios to the N-column
 * {@link TabularRenderModel} (both are wider than the 3-column statement layout). Cells are
 * formatted here; the renderers print them as given. A figure that could not be computed prints
 * as an em dash with its reason — never as 0.00.
 */
@Component
public class FinancialAnalysisFlattener {

    public TabularRenderModel flatten(ChangesInEquityDto dto) {
        List<String> headerLines = letterhead(dto.company(), dto.header().companyName());
        headerLines.add("Period " + dto.header().periodLabel() + " — " + dto.header().branchLabel());
        headerLines.add("Currency: " + dto.header().currency());

        List<Column> columns = List.of(
                new Column("Component", Align.LEFT),
                new Column("Opening", Align.RIGHT),
                new Column("Profit for the period", Align.RIGHT),
                new Column("Opening balances posted", Align.RIGHT),
                new Column("Capital introduced & other credits", Align.RIGHT),
                new Column("Drawings, dividends & other debits", Align.RIGHT),
                new Column("Transfers", Align.RIGHT),
                new Column("Closing", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (EquityMovementRowDto r : dto.rows()) {
            rows.add(equityCells(componentLabel(r), r));
        }
        List<String> totals = equityCells("Total equity", dto.totals());

        List<String> footer = new ArrayList<>();
        footer.add((dto.reconciliation().ties() ? "Ties: " : "DOES NOT TIE: ")
                + dto.reconciliation().label()
                + " (Balance Sheet equity " + amt(dto.balanceSheetClosingEquity()) + ")");
        footer.add((dto.transfersCheck().ties() ? "Ties: " : "DOES NOT TIE: ")
                + dto.transfersCheck().label());
        footer.add("Profit for the period equals the Income Statement net profit, "
                + "excluding year-end close journals.");

        return new TabularRenderModel("Statement of Changes in Equity", headerLines,
                dto.header().generatedAt().toString(), columns, rows, totals, footer);
    }

    public TabularRenderModel flatten(FinancialRatiosDto dto) {
        List<String> headerLines = letterhead(dto.company(), dto.header().companyName());
        headerLines.add("Period " + dto.header().periodLabel() + " (" + dto.periodDays() + " days) — "
                + dto.header().branchLabel());
        headerLines.add("Currency: " + dto.header().currency());

        List<Column> columns = List.of(
                new Column("Ratio", Align.LEFT),
                new Column("Formula", Align.LEFT),
                new Column("Inputs", Align.LEFT),
                new Column("Result", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.ratios().size());
        for (FinancialRatioDto r : dto.ratios()) {
            String inputs = r.inputs().stream()
                    .map(this::input)
                    .collect(Collectors.joining("; "));
            String result = r.value() != null
                    ? amt(r.value()) + " " + r.unit()
                    : "— (" + r.unavailableReason() + ")";
            String name = r.note() != null ? r.name() + " — " + r.note() : r.name();
            rows.add(List.of(name, r.formula(), inputs, result));
        }

        List<String> footer = new ArrayList<>(dto.notes());
        if (!dto.incomeStatementTies() || !dto.balanceSheetTies()) {
            footer.add("WARNING: a source statement does not pass its own self-check; "
                    + "investigate before relying on these ratios.");
        }
        return new TabularRenderModel("Financial Ratios", headerLines,
                dto.header().generatedAt().toString(), columns, rows, null, footer);
    }

    // -------------------------------------------------------------------------

    private List<String> equityCells(String label, EquityMovementRowDto r) {
        return List.of(label, amt(r.opening()), amt(r.profitForPeriod()),
                amt(r.openingBalancesPosted()), amt(r.capitalIntroduced()),
                amt(r.drawingsAndDividends()), amt(r.transfers()), amt(r.closing()));
    }

    private String componentLabel(EquityMovementRowDto r) {
        String base = r.accountCode() != null ? r.accountCode() + " " + r.component() : r.component();
        return r.ties() ? base : base + " (does not tie)";
    }

    private String input(RatioInputDto i) {
        return i.label() + " " + amt(i.amount());
    }

    private static List<String> letterhead(ReportCompanyHeaderDto c, String fallbackName) {
        List<String> lines = new ArrayList<>();
        if (c == null) {
            if (fallbackName != null) lines.add(fallbackName);
            return lines;
        }
        lines.add(c.name() != null ? c.name() : (fallbackName != null ? fallbackName : ""));
        StringBuilder address = new StringBuilder();
        for (String part : new String[]{c.addressLine1(), c.addressLine2(), c.city(), c.region(), c.country()}) {
            if (part == null || part.isBlank()) continue;
            if (address.length() > 0) address.append(", ");
            address.append(part);
        }
        if (address.length() > 0) lines.add(address.toString());
        if (c.taxId() != null) lines.add("TIN: " + c.taxId());
        if (c.vrn() != null) lines.add("VRN: " + c.vrn());
        return lines;
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format(Locale.US, "%,.2f", v) : "—";
    }
}
