package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.ReconciliationDto;
import com.erp.modules.reporting.domain.dto.StatementHeaderDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Assembles a Balance Sheet from cumulative as-at account balances (ADR-0018 D-6).
 *
 * <p>The equity fold is <b>inception-to-date</b> (not FY-to-date): because there is no year-end
 * close yet, INCOME/EXPENSE accounts are never zeroed, so the full {@code Σ INCOME − Σ EXPENSE}
 * for {@code posting_date <= asAtDate} is folded into equity. Presentation splits this into:
 * <ul>
 *   <li>"Retained earnings — prior years (unclosed)" = net income for {@code posting_date < fyStart}
 *   <li>"Current-year earnings" = net income for {@code [fyStart, asAtDate]}
 * </ul>
 * The split is cosmetic; the balance guarantee depends only on the total fold (D-6, step 2).
 *
 * <p>Self-check (BR-REP-02): {@code totalAssets == totalLiabilities + totalEquity}.
 * A non-balancing result surfaces as {@code ReconciliationDto.ties=false} — never plugged.
 *
 * <p><b>Branch Balance Sheets.</b> With a branch {@link StatementScope} every figure — account
 * balances and the earnings fold — is read from that branch's journal lines only. It balances on
 * its own because the posting engine stamps one branch on every line of an entry. Should an entry
 * ever carry lines of different branches, the amount by which this branch's lines fail to net to
 * zero is shown as an explicit current-liability line, {@value #INTER_BRANCH_LINE}, computed
 * independently from all of the branch's lines (it is a measured figure, not the balancing
 * difference — a classification fault would still break the bar). Caveats a reader must know,
 * stated on the screen too:
 * <ul>
 *   <li>Stock transfers between branches move quantities, not ledger value (no journal is posted),
 *       so a branch's Inventory reflects what it bought and sold, not what it holds — the receiving
 *       branch can even show negative inventory. The company total is right.
 *   <li>Company-level journals (manual journals, AR opening balances, FX revaluation, the year-end
 *       close) carry no branch. They appear only in the company-wide statement and in the
 *       "company-level entries" slice; after a year-end close, each branch keeps its closed
 *       years under "prior years (unclosed)" because the close itself is company-level.
 * </ul>
 */
@Component
public class BalanceSheetBuilder {

    public static final String PRIOR_YEARS_LINE  = "Retained earnings — prior years (unclosed)";
    public static final String CURRENT_YEAR_LINE = "Current-year earnings";
    public static final String INTER_BRANCH_LINE = "Inter-branch balance (entries spanning branches)";

    /** Inception: earlier than any posting the ledger can hold. */
    static final LocalDate INCEPTION = LocalDate.of(1900, 1, 1);

    private final AccountMovementQuery    movementQuery;
    private final StatementClassifier     classifier;
    private final FiscalYearStartResolver fyStartResolver;

    public BalanceSheetBuilder(AccountMovementQuery movementQuery,
                                StatementClassifier classifier,
                                FiscalYearStartResolver fyStartResolver) {
        this.movementQuery   = movementQuery;
        this.classifier      = classifier;
        this.fyStartResolver = fyStartResolver;
    }

    /**
     * Builds the company-wide Balance Sheet as-at {@code asAtDate} (primary) and
     * {@code compareAsAt} (comparative). {@code assertCanActIn} must have been called before.
     */
    public BalanceSheetDto build(Long companyId, String companyName, String currency,
                                  LocalDate asAtDate, LocalDate compareAsAt) {
        return build(companyId, companyName, currency, StatementScope.companyWide(), asAtDate, compareAsAt);
    }

    /** Builds the Balance Sheet inside a {@link StatementScope}; see the class note on branches. */
    public BalanceSheetDto build(Long companyId, String companyName, String currency,
                                  StatementScope scope,
                                  LocalDate asAtDate, LocalDate compareAsAt) {

        Map<Long, ChartOfAccount> accountMap = movementQuery.accountMapForCompany(companyId);

        // Cumulative balances as-at each date
        Map<Long, BigDecimal[]> current     = movementQuery.cumulativeByAccountAsAt(companyId, scope, asAtDate);
        Map<Long, BigDecimal[]> comparative = movementQuery.cumulativeByAccountAsAt(companyId, scope, compareAsAt);

        // ---- Equity fold: INCEPTION-TO-DATE (D-6, step 2) ----
        // FY start for the primary date — used to split the fold for presentation
        LocalDate fyStart    = fyStartResolver.fyStart(companyId, asAtDate);
        LocalDate cmpFyStart = fyStartResolver.fyStart(companyId, compareAsAt);

        // Prior-years retained = net income for posting_date < fyStart (i.e. up to fyStart-1).
        // The closing journal is INCLUDED here — it is what moves a closed year into 3900.
        BigDecimal priorRetainedCur = movementQuery.netIncomeForPeriod(
                companyId, scope, INCEPTION, fyStart.minusDays(1), false);
        BigDecimal currentYearCur   = movementQuery.netIncomeForPeriod(
                companyId, scope, fyStart, asAtDate, false);

        BigDecimal priorRetainedCmp = movementQuery.netIncomeForPeriod(
                companyId, scope, INCEPTION, cmpFyStart.minusDays(1), false);
        BigDecimal currentYearCmp   = movementQuery.netIncomeForPeriod(
                companyId, scope, cmpFyStart, compareAsAt, false);

        // ---- Classify posted accounts into BS sections ----
        Map<StatementSection, List<StatementLineDto>> sections = initSectionMap();

        for (ChartOfAccount acct : accountMap.values()) {
            AccountType type = acct.getAccountType();
            if (type == AccountType.INCOME || type == AccountType.EXPENSE) continue; // P&L accounts, not BS

            StatementSection sec = classifier.classify(type, acct.getAccountCode());
            BigDecimal cur = presentedBalance(acct, current.get(acct.getId()));
            BigDecimal cmp = presentedBalance(acct, comparative.get(acct.getId()));
            sections.get(sec).add(new StatementLineDto(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    AmountPairDto.of(cur, cmp)));
        }

        sections.values().forEach(lines -> lines.sort(
                (a, b) -> a.accountCode().compareTo(b.accountCode())));

        // ---- Inter-branch balance (branch / company-level slices only; normally zero) ----
        if (!scope.isCompanyWide()) {
            BigDecimal interCur = movementQuery.scopeNetDebitAsAt(companyId, scope, asAtDate);
            BigDecimal interCmp = movementQuery.scopeNetDebitAsAt(companyId, scope, compareAsAt);
            if (interCur.signum() != 0 || interCmp.signum() != 0) {
                // A slice whose lines net to a DEBIT of D has D more on its asset side than its
                // liability + equity side: the balancing claim on other branches is a credit of D.
                sections.get(StatementSection.CURRENT_LIABILITIES).add(new StatementLineDto(
                        null, null, null, INTER_BRANCH_LINE, AmountPairDto.of(interCur, interCmp)));
            }
        }

        // ---- Add synthetic equity-fold lines ----
        List<StatementLineDto> equityLines = sections.get(StatementSection.EQUITY);
        equityLines.add(new StatementLineDto(null, null, null,
                PRIOR_YEARS_LINE,
                AmountPairDto.of(priorRetainedCur, priorRetainedCmp)));
        equityLines.add(new StatementLineDto(null, null, null,
                CURRENT_YEAR_LINE,
                AmountPairDto.of(currentYearCur, currentYearCmp)));

        // ---- Section subtotals ----
        AmountPairDto curAssetsSubtotal    = sumLines(sections.get(StatementSection.CURRENT_ASSETS));
        AmountPairDto nonCurAssetsSubtotal = sumLines(sections.get(StatementSection.NON_CURRENT_ASSETS));
        AmountPairDto curLiabSubtotal      = sumLines(sections.get(StatementSection.CURRENT_LIABILITIES));
        AmountPairDto nonCurLiabSubtotal   = sumLines(sections.get(StatementSection.NON_CURRENT_LIABILITIES));
        AmountPairDto equitySubtotal       = sumLines(sections.get(StatementSection.EQUITY));

        AmountPairDto totalAssets = AmountPairDto.of(
                curAssetsSubtotal.current().add(nonCurAssetsSubtotal.current()),
                curAssetsSubtotal.comparative().add(nonCurAssetsSubtotal.comparative()));
        AmountPairDto totalLiab = AmountPairDto.of(
                curLiabSubtotal.current().add(nonCurLiabSubtotal.current()),
                curLiabSubtotal.comparative().add(nonCurLiabSubtotal.comparative()));
        AmountPairDto totalEquity = equitySubtotal;
        AmountPairDto totalLiabEquity = AmountPairDto.of(
                totalLiab.current().add(totalEquity.current()),
                totalLiab.comparative().add(totalEquity.comparative()));

        // Self-check (BR-REP-02): ASSET == LIABILITY + EQUITY
        ReconciliationDto recon = ReconciliationDto.of(
                "ASSET == LIABILITY + EQUITY", totalAssets, totalLiabEquity);

        List<StatementSectionDto> sectionList = List.of(
                new StatementSectionDto(StatementSection.CURRENT_ASSETS, "Current Assets",
                        sections.get(StatementSection.CURRENT_ASSETS), curAssetsSubtotal),
                new StatementSectionDto(StatementSection.NON_CURRENT_ASSETS, "Non-Current Assets",
                        sections.get(StatementSection.NON_CURRENT_ASSETS), nonCurAssetsSubtotal),
                new StatementSectionDto(StatementSection.CURRENT_LIABILITIES, "Current Liabilities",
                        sections.get(StatementSection.CURRENT_LIABILITIES), curLiabSubtotal),
                new StatementSectionDto(StatementSection.NON_CURRENT_LIABILITIES, "Non-Current Liabilities",
                        sections.get(StatementSection.NON_CURRENT_LIABILITIES), nonCurLiabSubtotal),
                new StatementSectionDto(StatementSection.EQUITY, "Equity",
                        sections.get(StatementSection.EQUITY), equitySubtotal));

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, companyName, currency,
                "As at " + asAtDate,
                "As at " + compareAsAt,
                null, null, asAtDate,
                Instant.now(), scope.branchUid(), scope.label());

        return new BalanceSheetDto(header, sectionList, totalAssets, totalLiab, totalEquity, recon);
    }

    // -------------------------------------------------------------------------

    /**
     * Net balance presented positive per the account's normal balance.
     * ASSET (DEBIT-normal): debit − credit; LIABILITY/EQUITY (CREDIT-normal): credit − debit.
     */
    private BigDecimal presentedBalance(ChartOfAccount acct, BigDecimal[] sums) {
        if (sums == null) return BigDecimal.ZERO;
        return switch (acct.getNormalBalance()) {
            case DEBIT  -> sums[0].subtract(sums[1]);
            case CREDIT -> sums[1].subtract(sums[0]);
        };
    }

    private AmountPairDto sumLines(List<StatementLineDto> lines) {
        BigDecimal cur = lines.stream().map(l -> l.amounts().current()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cmp = lines.stream().map(l -> l.amounts().comparative()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return AmountPairDto.of(cur, cmp);
    }

    private Map<StatementSection, List<StatementLineDto>> initSectionMap() {
        Map<StatementSection, List<StatementLineDto>> map = new EnumMap<>(StatementSection.class);
        for (StatementSection s : new StatementSection[]{
                StatementSection.CURRENT_ASSETS, StatementSection.NON_CURRENT_ASSETS,
                StatementSection.CURRENT_LIABILITIES, StatementSection.NON_CURRENT_LIABILITIES,
                StatementSection.EQUITY}) {
            map.put(s, new ArrayList<>());
        }
        return map;
    }
}
