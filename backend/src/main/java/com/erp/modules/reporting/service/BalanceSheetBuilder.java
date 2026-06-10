package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
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
 */
@Component
public class BalanceSheetBuilder {

    private final AccountMovementQuery   movementQuery;
    private final StatementClassifier    classifier;
    private final FiscalPeriodRepository fiscalPeriodRepo;

    public BalanceSheetBuilder(AccountMovementQuery movementQuery,
                                StatementClassifier classifier,
                                FiscalPeriodRepository fiscalPeriodRepo) {
        this.movementQuery    = movementQuery;
        this.classifier       = classifier;
        this.fiscalPeriodRepo = fiscalPeriodRepo;
    }

    /**
     * Builds the Balance Sheet as-at {@code asAtDate} (primary) and {@code compareAsAt}
     * (comparative). {@code assertCanActIn} must have been called before this method.
     */
    public BalanceSheetDto build(Long companyId, String companyName, String currency,
                                  LocalDate asAtDate, LocalDate compareAsAt) {

        Map<Long, ChartOfAccount> accountMap = movementQuery.accountMapForCompany(companyId);

        // Cumulative balances as-at each date
        Map<Long, BigDecimal[]> current     = movementQuery.cumulativeByAccountAsAt(companyId, asAtDate);
        Map<Long, BigDecimal[]> comparative = movementQuery.cumulativeByAccountAsAt(companyId, compareAsAt);

        // ---- Equity fold: INCEPTION-TO-DATE (D-6, step 2) ----
        // FY start for the primary date — used to split the fold for presentation
        LocalDate fyStart = resolveFyStart(companyId, asAtDate);
        LocalDate cmpFyStart = resolveFyStart(companyId, compareAsAt);

        // Prior-years retained = net income for posting_date < fyStart (i.e. up to fyStart-1)
        BigDecimal priorRetainedCur = movementQuery.netIncomeForPeriod(
                companyId, LocalDate.of(1900, 1, 1), fyStart.minusDays(1));
        BigDecimal currentYearCur   = movementQuery.netIncomeForPeriod(companyId, fyStart, asAtDate);

        BigDecimal priorRetainedCmp = movementQuery.netIncomeForPeriod(
                companyId, LocalDate.of(1900, 1, 1), cmpFyStart.minusDays(1));
        BigDecimal currentYearCmp   = movementQuery.netIncomeForPeriod(companyId, cmpFyStart, compareAsAt);

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

        // ---- Add synthetic equity-fold lines ----
        List<StatementLineDto> equityLines = sections.get(StatementSection.EQUITY);
        equityLines.add(new StatementLineDto(null, null, null,
                "Retained earnings — prior years (unclosed)",
                AmountPairDto.of(priorRetainedCur, priorRetainedCmp)));
        equityLines.add(new StatementLineDto(null, null, null,
                "Current-year earnings",
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
                Instant.now());

        return new BalanceSheetDto(header, sectionList, totalAssets, totalLiab, totalEquity, recon);
    }

    // -------------------------------------------------------------------------

    /**
     * Resolves the fiscal-year start date containing {@code date} for {@code companyId}.
     * If no fiscal year covers the date, falls back to Jan 1 of that calendar year (D-6, step 1).
     */
    private LocalDate resolveFyStart(Long companyId, LocalDate date) {
        List<FiscalPeriod> allPeriods = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(companyId);
        // Find the fiscal year covering this date
        Long fyId = allPeriods.stream()
                .filter(p -> !p.getStartDate().isAfter(date) && !p.getEndDate().isBefore(date))
                .map(FiscalPeriod::getFiscalYearId)
                .findFirst()
                .orElse(null);
        if (fyId == null) {
            // No fiscal year covers this date — fall back to calendar year start
            return LocalDate.of(date.getYear(), 1, 1);
        }
        // Period 1 of that fiscal year holds the FY start date
        return allPeriods.stream()
                .filter(p -> fyId.equals(p.getFiscalYearId()))
                .map(FiscalPeriod::getStartDate)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.of(date.getYear(), 1, 1));
    }

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
