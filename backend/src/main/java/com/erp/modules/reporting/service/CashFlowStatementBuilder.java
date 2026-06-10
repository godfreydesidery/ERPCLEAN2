package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
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
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Assembles a Cash-Flow Statement (indirect method) (ADR-0018 D-7, FR-REP-03).
 *
 * <p>Sign convention: per-account period change = {@code Δdebit − Δcredit} (net-debit movement).
 * Working-capital contribution to OPERATING = {@code −(Δdebit − Δcredit)}: a rise in a
 * debit-normal (asset) account uses cash (subtract); a rise in a credit-normal (liability) account
 * provides cash (its net-debit Δ is negative → negating it adds to operating cash).
 *
 * <p>Cash-equivalent accounts (from {@link CashEquivalentAccountResolver}) are excluded from
 * working-capital / investing / financing sections — they are the tie-out denominator, not addends.
 *
 * <p>Self-check (BR-REP-04): {@code netChangeInCash == Δ(cash-equivalent GL accounts)}.
 */
@Component
public class CashFlowStatementBuilder {

    private final AccountMovementQuery          movementQuery;
    private final StatementClassifier           classifier;
    private final CashEquivalentAccountResolver cashResolver;

    public CashFlowStatementBuilder(AccountMovementQuery movementQuery,
                                     StatementClassifier classifier,
                                     CashEquivalentAccountResolver cashResolver) {
        this.movementQuery = movementQuery;
        this.classifier    = classifier;
        this.cashResolver  = cashResolver;
    }

    /**
     * Builds the Cash-Flow Statement over [from, to] and comparative [cmpFrom, cmpTo].
     * {@code assertCanActIn} must have been called before this method.
     */
    public CashFlowStatementDto build(Long companyId, String companyName, String currency,
                                       LocalDate from, LocalDate to,
                                       LocalDate cmpFrom, LocalDate cmpTo) {

        Map<Long, ChartOfAccount> accountMap  = movementQuery.accountMapForCompany(companyId);
        Set<Long> cashAccountIds = Set.copyOf(cashResolver.cashAccountIds(companyId));

        // Net income (from P&L builder)
        // Use type aggregates directly to avoid double-building
        BigDecimal netIncomeCur = movementQuery.netIncomeForPeriod(companyId, from, to);
        BigDecimal netIncomeCmp = movementQuery.netIncomeForPeriod(companyId, cmpFrom, cmpTo);

        // Period changes per account: balanceAsAt(to) − balanceAsAt(from−1)
        Map<Long, BigDecimal[]> cumTo    = movementQuery.cumulativeByAccountAsAt(companyId, to);
        Map<Long, BigDecimal[]> cumFrom1 = movementQuery.cumulativeByAccountAsAt(companyId, from.minusDays(1));
        Map<Long, BigDecimal[]> cumCmpTo    = movementQuery.cumulativeByAccountAsAt(companyId, cmpTo);
        Map<Long, BigDecimal[]> cumCmpFrom1 = movementQuery.cumulativeByAccountAsAt(companyId, cmpFrom.minusDays(1));

        // Opening / closing cash balances
        BigDecimal openingCashCur  = sumCash(cashAccountIds, cumFrom1);
        BigDecimal closingCashCur  = sumCash(cashAccountIds, cumTo);
        BigDecimal openingCashCmp  = sumCash(cashAccountIds, cumCmpFrom1);
        BigDecimal closingCashCmp  = sumCash(cashAccountIds, cumCmpTo);

        BigDecimal deltaCashCur    = closingCashCur.subtract(openingCashCur);
        BigDecimal deltaCashCmp    = closingCashCmp.subtract(openingCashCmp);

        // Build CF sections
        Map<StatementSection, List<StatementLineDto>> sections = new EnumMap<>(StatementSection.class);
        for (StatementSection s : new StatementSection[]{
                StatementSection.OPERATING, StatementSection.INVESTING, StatementSection.FINANCING}) {
            sections.put(s, new ArrayList<>());
        }

        // Start OPERATING with net income line
        sections.get(StatementSection.OPERATING).add(new StatementLineDto(
                null, null, null, "Net income",
                AmountPairDto.of(netIncomeCur, netIncomeCmp)));

        for (ChartOfAccount acct : accountMap.values()) {
            AccountType type = acct.getAccountType();
            // Skip P&L accounts (net income already captured) and cash-equivalent accounts
            boolean isPl   = type == AccountType.INCOME || type == AccountType.EXPENSE;
            boolean isCash = cashAccountIds.contains(acct.getId());
            if (isPl || isCash) continue;

            StatementSection cfSection = classifier.classifyForCashFlow(type, acct.getAccountCode());

            // Per-account period change (net-debit movement): Δdebit − Δcredit
            BigDecimal deltaCur = netDebitDelta(acct.getId(), cumTo, cumFrom1);
            BigDecimal deltaCmp = netDebitDelta(acct.getId(), cumCmpTo, cumCmpFrom1);

            // CF contribution = −(Δ net-debit) per D-7 sign convention
            BigDecimal cfCur = deltaCur.negate();
            BigDecimal cfCmp = deltaCmp.negate();

            sections.get(cfSection).add(new StatementLineDto(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    AmountPairDto.of(cfCur, cfCmp)));
        }

        sections.values().forEach(lines -> lines.sort(
                (a, b) -> nullSafeCode(a).compareTo(nullSafeCode(b))));

        AmountPairDto opSubtotal  = sumLines(sections.get(StatementSection.OPERATING));
        AmountPairDto invSubtotal = sumLines(sections.get(StatementSection.INVESTING));
        AmountPairDto finSubtotal = sumLines(sections.get(StatementSection.FINANCING));

        AmountPairDto netChange = AmountPairDto.of(
                opSubtotal.current().add(invSubtotal.current()).add(finSubtotal.current()),
                opSubtotal.comparative().add(invSubtotal.comparative()).add(finSubtotal.comparative()));

        AmountPairDto openingCash  = AmountPairDto.of(openingCashCur, openingCashCmp);
        AmountPairDto closingCash  = AmountPairDto.of(closingCashCur, closingCashCmp);
        AmountPairDto expectedDelta = AmountPairDto.of(deltaCashCur, deltaCashCmp);

        // Self-check (BR-REP-04)
        ReconciliationDto recon = ReconciliationDto.of(
                "CF net change == Cash + Bank GL movement", netChange, expectedDelta);

        List<StatementSectionDto> sectionList = List.of(
                new StatementSectionDto(StatementSection.OPERATING,  "Operating Activities",
                        sections.get(StatementSection.OPERATING),  opSubtotal),
                new StatementSectionDto(StatementSection.INVESTING,  "Investing Activities",
                        sections.get(StatementSection.INVESTING),  invSubtotal),
                new StatementSectionDto(StatementSection.FINANCING,  "Financing Activities",
                        sections.get(StatementSection.FINANCING),  finSubtotal));

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, companyName, currency,
                from + " – " + to,
                cmpFrom + " – " + cmpTo,
                from, to, null,
                Instant.now());

        return new CashFlowStatementDto(header, sectionList, netChange, openingCash, closingCash, recon);
    }

    // -------------------------------------------------------------------------

    /** Sum net-debit balances (debit − credit) for the cash-equivalent accounts. */
    private BigDecimal sumCash(Set<Long> cashIds, Map<Long, BigDecimal[]> cumulative) {
        BigDecimal total = BigDecimal.ZERO;
        for (Long id : cashIds) {
            BigDecimal[] row = cumulative.get(id);
            if (row != null) total = total.add(row[0]).subtract(row[1]);
        }
        return total;
    }

    /** Net-debit movement for one account: (Σdebit_to − Σdebit_from1) − (Σcredit_to − Σcredit_from1). */
    private BigDecimal netDebitDelta(Long accountId,
                                      Map<Long, BigDecimal[]> cumTo,
                                      Map<Long, BigDecimal[]> cumFrom1) {
        BigDecimal[] to   = cumTo.getOrDefault(accountId,   new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal[] from = cumFrom1.getOrDefault(accountId, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal deltaD = to[0].subtract(from[0]);
        BigDecimal deltaC = to[1].subtract(from[1]);
        return deltaD.subtract(deltaC);
    }

    private AmountPairDto sumLines(List<StatementLineDto> lines) {
        BigDecimal cur = lines.stream().map(l -> l.amounts().current()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cmp = lines.stream().map(l -> l.amounts().comparative()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return AmountPairDto.of(cur, cmp);
    }

    private String nullSafeCode(StatementLineDto l) {
        return l.accountCode() != null ? l.accountCode() : "";
    }
}
