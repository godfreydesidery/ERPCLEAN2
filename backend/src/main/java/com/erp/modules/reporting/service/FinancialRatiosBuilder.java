package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.FinancialRatioDto;
import com.erp.modules.reporting.domain.dto.FinancialRatiosDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.dto.RatioInputDto;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.dto.StatementHeaderDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Financial ratios over [from, to], computed ONLY from figures the statement builders already
 * produce — no new classification logic:
 * <ul>
 *   <li>the Income Statement over [from, to] (revenue, cost of sales, gross and net profit);
 *   <li>the Balance Sheet as-at {@code to} (closing) with comparative as-at {@code from − 1}
 *       (opening) — section subtotals and totals, and the lines of the accounts the chart of
 *       accounts already flags as Inventory / AR / AP control accounts ({@code control_type}).
 * </ul>
 * "Average" means (opening + closing) / 2 of the Balance Sheet figure.
 *
 * <p>A ratio whose denominator is zero has a null value and says why — never a zero or an
 * infinity. Results are rounded half-up to 2 dp; intermediate arithmetic keeps 10 dp.
 *
 * <p>With a branch {@link StatementScope} both statements are that branch's, so the ratios are
 * too — and they inherit the branch caveats (see {@link BalanceSheetBuilder}): branch inventory,
 * and so the inventory ratios, are distorted by stock transfers that move no ledger value.
 */
@Component
public class FinancialRatiosBuilder {

    private static final int SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final IncomeStatementBuilder plBuilder;
    private final BalanceSheetBuilder    bsBuilder;
    private final AccountMovementQuery   movementQuery;

    public FinancialRatiosBuilder(IncomeStatementBuilder plBuilder,
                                  BalanceSheetBuilder bsBuilder,
                                  AccountMovementQuery movementQuery) {
        this.plBuilder     = plBuilder;
        this.bsBuilder     = bsBuilder;
        this.movementQuery = movementQuery;
    }

    public FinancialRatiosDto build(Long companyId, String companyName, String currency,
                                    ReportCompanyHeaderDto company, StatementScope scope,
                                    LocalDate from, LocalDate to,
                                    LocalDate plCmpFrom, LocalDate plCmpTo) {
        IncomeStatementDto pl = plBuilder.build(companyId, companyName, currency, scope,
                from, to, plCmpFrom, plCmpTo);
        BalanceSheetDto bs = bsBuilder.build(companyId, companyName, currency, scope,
                to, from.minusDays(1));
        Map<Long, ChartOfAccount> accounts = movementQuery.accountMapForCompany(companyId);
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        // ---- Income Statement figures (current period) ----
        BigDecimal revenue     = section(pl.sections(), StatementSection.REVENUE).current();
        BigDecimal costOfSales = section(pl.sections(), StatementSection.COST_OF_SALES).current();
        BigDecimal grossProfit = pl.grossProfit().current();
        BigDecimal netProfit   = pl.netProfit().current();

        // ---- Balance Sheet figures (current = closing, comparative = opening) ----
        AmountPairDto currentAssets = section(bs.sections(), StatementSection.CURRENT_ASSETS);
        AmountPairDto currentLiab   = section(bs.sections(), StatementSection.CURRENT_LIABILITIES);
        AmountPairDto inventory   = controlled(bs, accounts, StatementSection.CURRENT_ASSETS,
                AccountType.ASSET, ControlType.INVENTORY);
        AmountPairDto receivables = controlled(bs, accounts, StatementSection.CURRENT_ASSETS,
                AccountType.ASSET, ControlType.AR);
        AmountPairDto payables    = controlled(bs, accounts, StatementSection.CURRENT_LIABILITIES,
                AccountType.LIABILITY, ControlType.AP);
        AmountPairDto totalLiab   = bs.totalLiabilities();
        AmountPairDto totalEquity = bs.totalEquity();

        BigDecimal avgInventory   = average(inventory);
        BigDecimal avgReceivables = average(receivables);
        BigDecimal avgPayables    = average(payables);
        BigDecimal avgEquity      = average(totalEquity);
        BigDecimal daysBd         = BigDecimal.valueOf(days);

        List<FinancialRatioDto> ratios = new ArrayList<>();

        ratios.add(ratio("CURRENT_RATIO", "Current ratio",
                "Current assets ÷ Current liabilities", "x",
                List.of(in("Current assets", currentAssets.current()),
                        in("Current liabilities", currentLiab.current())),
                currentAssets.current(), currentLiab.current(), BigDecimal.ONE,
                "Current liabilities are zero.", null));

        BigDecimal quickAssets = currentAssets.current().subtract(inventory.current());
        ratios.add(ratio("QUICK_RATIO", "Quick ratio",
                "(Current assets − Inventory) ÷ Current liabilities", "x",
                List.of(in("Current assets", currentAssets.current()),
                        in("Inventory", inventory.current()),
                        in("Current liabilities", currentLiab.current())),
                quickAssets, currentLiab.current(), BigDecimal.ONE,
                "Current liabilities are zero.", null));

        ratios.add(ratio("GROSS_MARGIN", "Gross margin",
                "Gross profit ÷ Revenue × 100", "%",
                List.of(in("Gross profit", grossProfit), in("Revenue", revenue)),
                grossProfit, revenue, HUNDRED,
                "There is no revenue in the period.", null));

        ratios.add(ratio("NET_MARGIN", "Net margin",
                "Net profit ÷ Revenue × 100", "%",
                List.of(in("Net profit", netProfit), in("Revenue", revenue)),
                netProfit, revenue, HUNDRED,
                "There is no revenue in the period.", null));

        ratios.add(ratio("DEBT_TO_EQUITY", "Debt-to-equity",
                "Total liabilities ÷ Total equity", "x",
                List.of(in("Total liabilities", totalLiab.current()),
                        in("Total equity", totalEquity.current())),
                totalLiab.current(), totalEquity.current(), BigDecimal.ONE,
                "Total equity is zero.",
                totalEquity.current().signum() < 0
                        ? "Equity is negative (liabilities exceed assets), so this ratio is negative."
                        : null));

        ratios.add(ratio("RETURN_ON_EQUITY", "Return on equity",
                "Net profit ÷ Average equity × 100", "%",
                List.of(in("Net profit", netProfit),
                        in("Opening equity", totalEquity.comparative()),
                        in("Closing equity", totalEquity.current()),
                        in("Average equity", avgEquity)),
                netProfit, avgEquity, HUNDRED,
                "Average equity is zero.",
                "For the selected period, not annualised."));

        ratios.add(ratio("INVENTORY_TURNOVER", "Inventory turnover",
                "Cost of sales ÷ Average inventory", "x",
                List.of(in("Cost of sales", costOfSales),
                        in("Opening inventory", inventory.comparative()),
                        in("Closing inventory", inventory.current()),
                        in("Average inventory", avgInventory)),
                costOfSales, avgInventory, BigDecimal.ONE,
                "Average inventory is zero.",
                "Times the stock turned over in the selected period, not annualised."));

        ratios.add(ratio("INVENTORY_DAYS", "Inventory days",
                "Average inventory ÷ Cost of sales × Days in period", "days",
                List.of(in("Average inventory", avgInventory),
                        in("Cost of sales", costOfSales),
                        in("Days in period", daysBd)),
                avgInventory, costOfSales, daysBd,
                "There is no cost of sales in the period.", null));

        ratios.add(ratio("DEBTOR_DAYS", "Debtor days (DSO)",
                "Average receivables ÷ Revenue × Days in period", "days",
                List.of(in("Average receivables", avgReceivables),
                        in("Revenue", revenue),
                        in("Days in period", daysBd)),
                avgReceivables, revenue, daysBd,
                "There is no revenue in the period.",
                "Uses total revenue: the ledger does not separate credit sales from cash sales, "
                        + "so a business with many cash sales shows fewer days than its credit "
                        + "customers actually take."));

        ratios.add(ratio("CREDITOR_DAYS", "Creditor days (DPO)",
                "Average payables ÷ Cost of sales × Days in period", "days",
                List.of(in("Average payables", avgPayables),
                        in("Cost of sales", costOfSales),
                        in("Days in period", daysBd)),
                avgPayables, costOfSales, daysBd,
                "There is no cost of sales in the period.",
                "Uses cost of sales in place of credit purchases, which the ledger does not "
                        + "total separately."));

        List<String> notes = List.of(
                "Every figure is read from the Income Statement for the period and the Balance "
                        + "Sheet at its opening and closing dates; averages are (opening + closing) ÷ 2.",
                "Inventory, receivables and payables are the accounts marked as Inventory, Accounts "
                        + "Receivable and Accounts Payable control accounts in the chart of accounts.",
                "A ratio shows no value when what it divides by is zero.");

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, companyName, currency,
                from + " – " + to,
                "Opening as at " + from.minusDays(1),
                from, to, null,
                Instant.now(), scope.branchUid(), scope.label());

        return new FinancialRatiosDto(header, company, days, ratios,
                pl.reconciliation().ties(), bs.reconciliation().ties(), notes);
    }

    // -------------------------------------------------------------------------

    /** value = numerator ÷ denominator × multiplier, or null + reason when the denominator is zero. */
    private static FinancialRatioDto ratio(String key, String name, String formula, String unit,
                                           List<RatioInputDto> inputs,
                                           BigDecimal numerator, BigDecimal denominator,
                                           BigDecimal multiplier, String zeroReason, String note) {
        if (denominator == null || denominator.signum() == 0) {
            return new FinancialRatioDto(key, name, formula, inputs, null, unit, zeroReason, note);
        }
        BigDecimal value = numerator.multiply(multiplier)
                .divide(denominator, SCALE, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        return new FinancialRatioDto(key, name, formula, inputs, value, unit, null, note);
    }

    private static RatioInputDto in(String label, BigDecimal amount) {
        return new RatioInputDto(label, amount);
    }

    private static BigDecimal average(AmountPairDto pair) {
        return pair.current().add(pair.comparative()).divide(TWO); // exact: halving always terminates
    }

    private static AmountPairDto section(List<StatementSectionDto> sections, StatementSection key) {
        return sections.stream()
                .filter(s -> s.sectionKey() == key)
                .map(StatementSectionDto::subtotal)
                .findFirst()
                .orElse(AmountPairDto.zero());
    }

    /** Σ of the Balance Sheet lines in {@code key} whose account carries the given control type. */
    private static AmountPairDto controlled(BalanceSheetDto bs, Map<Long, ChartOfAccount> accounts,
                                            StatementSection key, AccountType type, ControlType control) {
        BigDecimal cur = BigDecimal.ZERO;
        BigDecimal cmp = BigDecimal.ZERO;
        for (StatementSectionDto s : bs.sections()) {
            if (s.sectionKey() != key) continue;
            for (StatementLineDto line : s.lines()) {
                if (line.accountId() == null) continue;
                ChartOfAccount acct = accounts.get(line.accountId());
                if (acct == null || acct.getAccountType() != type || acct.getControlType() != control) continue;
                cur = cur.add(line.amounts().current());
                cmp = cmp.add(line.amounts().comparative());
            }
        }
        return AmountPairDto.of(cur, cmp);
    }
}
