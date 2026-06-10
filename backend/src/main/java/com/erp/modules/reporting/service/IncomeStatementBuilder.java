package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
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
 * Assembles an Income Statement / P&amp;L from SQL-aggregated account movements (ADR-0018 D-5).
 *
 * <p>Structure: REVENUE → COST_OF_SALES → gross profit subtotal → OPERATING_EXPENSES → net profit.
 * Self-check (BR-REP-03): netProfit == Σ INCOME net − Σ EXPENSE net from the type-level aggregate.
 */
@Component
public class IncomeStatementBuilder {

    private final AccountMovementQuery movementQuery;
    private final StatementClassifier  classifier;

    public IncomeStatementBuilder(AccountMovementQuery movementQuery,
                                   StatementClassifier classifier) {
        this.movementQuery = movementQuery;
        this.classifier    = classifier;
    }

    /**
     * Builds the P&amp;L for the primary period [from, to] and comparative period [cmpFrom, cmpTo].
     * {@code assertCanActIn} must have been called by the orchestrating service before this method.
     *
     * @param companyId     the tenant
     * @param companyName   display name for the header
     * @param currency      base currency code
     * @param from          primary period start
     * @param to            primary period end
     * @param cmpFrom       comparative period start
     * @param cmpTo         comparative period end
     */
    public IncomeStatementDto build(Long companyId, String companyName, String currency,
                                     LocalDate from, LocalDate to,
                                     LocalDate cmpFrom, LocalDate cmpTo) {

        // Account metadata
        Map<Long, ChartOfAccount> accountMap = movementQuery.accountMapForCompany(companyId);

        // (a) Period movements
        Map<Long, BigDecimal[]> current     = movementQuery.periodMovementByAccount(companyId, from, to);
        Map<Long, BigDecimal[]> comparative = movementQuery.periodMovementByAccount(companyId, cmpFrom, cmpTo);

        // (c) Type-level aggregates for the self-check
        Map<AccountType, BigDecimal[]> typesCurrent     = movementQuery.periodMovementByAccountType(companyId, from, to);
        Map<AccountType, BigDecimal[]> typesComparative = movementQuery.periodMovementByAccountType(companyId, cmpFrom, cmpTo);

        // Classify lines into sections
        Map<StatementSection, List<StatementLineDto>> sections = new EnumMap<>(StatementSection.class);
        for (StatementSection s : new StatementSection[]{
                StatementSection.REVENUE, StatementSection.COST_OF_SALES, StatementSection.OPERATING_EXPENSES}) {
            sections.put(s, new ArrayList<>());
        }

        for (ChartOfAccount acct : accountMap.values()) {
            if (acct.getAccountType() != AccountType.INCOME
                    && acct.getAccountType() != AccountType.EXPENSE) continue;

            StatementSection sec = classifier.classify(acct.getAccountType(), acct.getAccountCode());
            BigDecimal cur = presentedAmount(acct, current.get(acct.getId()));
            BigDecimal cmp = presentedAmount(acct, comparative.get(acct.getId()));
            sections.get(sec).add(new StatementLineDto(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    AmountPairDto.of(cur, cmp)));
        }

        // Sort lines by account code within each section
        sections.values().forEach(lines -> lines.sort(
                (a, b) -> a.accountCode().compareTo(b.accountCode())));

        // Section subtotals
        AmountPairDto revSubtotal  = sumLines(sections.get(StatementSection.REVENUE));
        AmountPairDto cogsSubtotal = sumLines(sections.get(StatementSection.COST_OF_SALES));
        AmountPairDto opexSubtotal = sumLines(sections.get(StatementSection.OPERATING_EXPENSES));

        // Gross profit and net profit
        AmountPairDto grossProfit = AmountPairDto.of(
                revSubtotal.current().subtract(cogsSubtotal.current()),
                revSubtotal.comparative().subtract(cogsSubtotal.comparative()));
        AmountPairDto netProfit = AmountPairDto.of(
                grossProfit.current().subtract(opexSubtotal.current()),
                grossProfit.comparative().subtract(opexSubtotal.comparative()));

        // Self-check (BR-REP-03): netProfit == Σ INCOME net − Σ EXPENSE net (type aggregate)
        BigDecimal checkCur = typeNetIncome(typesCurrent);
        BigDecimal checkCmp = typeNetIncome(typesComparative);
        AmountPairDto expected = AmountPairDto.of(checkCur, checkCmp);
        ReconciliationDto recon = ReconciliationDto.of(
                "P&L net == period INCOME − EXPENSE movement", netProfit, expected);

        // Assemble sections list
        List<StatementSectionDto> sectionList = List.of(
                new StatementSectionDto(StatementSection.REVENUE,
                        "Revenue", sections.get(StatementSection.REVENUE), revSubtotal),
                new StatementSectionDto(StatementSection.COST_OF_SALES,
                        "Cost of Sales", sections.get(StatementSection.COST_OF_SALES), cogsSubtotal),
                new StatementSectionDto(StatementSection.OPERATING_EXPENSES,
                        "Operating Expenses", sections.get(StatementSection.OPERATING_EXPENSES), opexSubtotal));

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, companyName, currency,
                from + " – " + to,
                cmpFrom + " – " + cmpTo,
                from, to, null,
                Instant.now());

        return new IncomeStatementDto(header, sectionList, grossProfit, netProfit, recon);
    }

    // -------------------------------------------------------------------------

    /**
     * Returns the presented positive amount for an account given its raw [debit, credit] movement.
     * INCOME (CREDIT-normal): credit − debit; EXPENSE (DEBIT-normal): debit − credit.
     */
    private BigDecimal presentedAmount(ChartOfAccount acct, BigDecimal[] sums) {
        if (sums == null) return BigDecimal.ZERO;
        return switch (acct.getNormalBalance()) {
            case CREDIT -> sums[1].subtract(sums[0]); // INCOME
            case DEBIT  -> sums[0].subtract(sums[1]); // EXPENSE
        };
    }

    private AmountPairDto sumLines(List<StatementLineDto> lines) {
        BigDecimal cur = lines.stream().map(l -> l.amounts().current()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cmp = lines.stream().map(l -> l.amounts().comparative()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return AmountPairDto.of(cur, cmp);
    }

    /** INCOME net − EXPENSE net from the type-level aggregate (the check value). */
    private BigDecimal typeNetIncome(Map<AccountType, BigDecimal[]> types) {
        BigDecimal[] income  = types.getOrDefault(AccountType.INCOME,  new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal[] expense = types.getOrDefault(AccountType.EXPENSE, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal incomeNet  = income[1].subtract(income[0]);   // CREDIT-normal
        BigDecimal expenseNet = expense[0].subtract(expense[1]);  // DEBIT-normal
        return incomeNet.subtract(expenseNet);
    }
}
