package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.ChangesInEquityDto;
import com.erp.modules.reporting.domain.dto.EquityMovementRowDto;
import com.erp.modules.reporting.domain.dto.ReconciliationDto;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.dto.StatementHeaderDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Statement of Changes in Equity over [from, to], company-wide.
 *
 * <p><b>Opening and closing come from the Balance Sheet</b> ({@link BalanceSheetBuilder}, as-at
 * {@code from − 1} and as-at {@code to}), component by component — so the closing column is the
 * Balance Sheet's equity by construction. The <b>movements are aggregated independently</b> from
 * the period's journal lines, and each component asserts {@code opening + movements == closing}.
 * A component that fails is a data-integrity alarm, never plugged (the reconciliation bar says so).
 *
 * <p>Movements, per component:
 * <ul>
 *   <li><b>Posted equity accounts</b> (3xxx) — the account's period lines, grouped by the journal's
 *       source: year-end close → <i>transfers</i>; opening-balance journals → <i>opening balances
 *       posted</i>; anything else → <i>capital introduced &amp; other credits</i> (its credits) and
 *       <i>drawings, dividends &amp; other debits</i> (its debits). The ledger carries no finer
 *       label than the journal source, so a capital injection and a correcting credit land in the
 *       same column — the column names say so.
 *   <li><b>Current-year earnings</b> — <i>profit for the period</i> = INCOME − EXPENSE excluding
 *       year-end close journals (the Income Statement's net profit); <i>transfers</i> = the close
 *       taking the year's result out (to 3900), less the roll-forward below.
 *   <li><b>Retained earnings — prior years (unclosed)</b> — <i>transfers</i> = the roll-forward:
 *       when the period crosses a fiscal-year start, the earnings that were "current year" at the
 *       opening date are "prior years" at the closing date. Computed as the exact net income of
 *       the window between the two fiscal-year starts, not as a balancing figure.
 * </ul>
 * The transfers column nets to zero — a second self-check.
 *
 * <p>Posts nothing (BR-REP-08). {@code assertCanActIn} must have been called by the service.
 */
@Component
public class ChangesInEquityBuilder {

    private static final Set<JournalSourceType> OPENING_SOURCES =
            Set.of(JournalSourceType.OPENING_BALANCE, JournalSourceType.OPENING_INVENTORY);

    private final BalanceSheetBuilder     bsBuilder;
    private final AccountMovementQuery    movementQuery;
    private final FiscalYearStartResolver fyStartResolver;

    public ChangesInEquityBuilder(BalanceSheetBuilder bsBuilder,
                                  AccountMovementQuery movementQuery,
                                  FiscalYearStartResolver fyStartResolver) {
        this.bsBuilder       = bsBuilder;
        this.movementQuery   = movementQuery;
        this.fyStartResolver = fyStartResolver;
    }

    public ChangesInEquityDto build(Long companyId, String companyName, String currency,
                                    ReportCompanyHeaderDto company,
                                    LocalDate from, LocalDate to) {
        StatementScope scope = StatementScope.companyWide();
        LocalDate openingDate = from.minusDays(1);

        // Opening (comparative) and closing (current) equity, straight off the Balance Sheet
        BalanceSheetDto bs = bsBuilder.build(companyId, companyName, currency, scope, to, openingDate);
        StatementSectionDto equity = bs.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.EQUITY)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Balance Sheet has no equity section"));

        // ---- Earnings-fold movements ----
        BigDecimal profit       = movementQuery.netIncomeForPeriod(companyId, scope, from, to, true);
        BigDecimal netAll       = movementQuery.netIncomeForPeriod(companyId, scope, from, to, false);
        BigDecimal closeOutOfPl = netAll.subtract(profit); // the year-end close's effect on earnings

        LocalDate fyOpen  = fyStartResolver.fyStart(companyId, openingDate);
        LocalDate fyClose = fyStartResolver.fyStart(companyId, to);
        BigDecimal rollForward = BigDecimal.ZERO;
        if (fyClose.isAfter(fyOpen)) {
            rollForward = movementQuery.netIncomeForPeriod(
                    companyId, scope, fyOpen, fyClose.minusDays(1), false);
        } else if (fyClose.isBefore(fyOpen)) {
            rollForward = movementQuery.netIncomeForPeriod(
                    companyId, scope, fyClose, fyOpen.minusDays(1), false).negate();
        }

        // ---- Posted-equity movements, by account and journal source ----
        Map<Long, BigDecimal[]> byAccount = new HashMap<>(); // [openingBal, credits, debits, transfers]
        for (Object[] r : movementQuery.equityMovementBySource(companyId, from, to)) {
            Long accountId = (Long) r[0];
            JournalSourceType source = (JournalSourceType) r[1];
            BigDecimal debit  = (BigDecimal) r[2];
            BigDecimal credit = (BigDecimal) r[3];
            BigDecimal[] acc = byAccount.computeIfAbsent(accountId,
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            if (source == JournalSourceType.YEAR_END_CLOSE) {
                acc[3] = acc[3].add(credit).subtract(debit);
            } else if (OPENING_SOURCES.contains(source)) {
                acc[0] = acc[0].add(credit).subtract(debit);
            } else {
                acc[1] = acc[1].add(credit);
                acc[2] = acc[2].subtract(debit);
            }
        }

        List<EquityMovementRowDto> rows = new ArrayList<>();
        for (StatementLineDto line : equity.lines()) {
            BigDecimal opening = line.amounts().comparative();
            BigDecimal closing = line.amounts().current();
            BigDecimal zero = BigDecimal.ZERO;
            EquityMovementRowDto row;
            if (line.accountId() != null) {
                BigDecimal[] m = byAccount.getOrDefault(line.accountId(),
                        new BigDecimal[]{zero, zero, zero, zero});
                row = row(line, false, opening, zero, m[0], m[1], m[2], m[3], closing);
            } else if (BalanceSheetBuilder.CURRENT_YEAR_LINE.equals(line.accountName())) {
                row = row(line, true, opening, profit, zero, zero, zero,
                        closeOutOfPl.subtract(rollForward), closing);
            } else if (BalanceSheetBuilder.PRIOR_YEARS_LINE.equals(line.accountName())) {
                row = row(line, true, opening, zero, zero, zero, zero, rollForward, closing);
            } else {
                // Unknown synthetic line: show it, with no movements; its tie flag will say if it moved.
                row = row(line, true, opening, zero, zero, zero, zero, zero, closing);
            }
            rows.add(row);
        }

        EquityMovementRowDto totals = totalsOf(rows);

        BigDecimal bsOpening = bs.totalEquity().comparative();
        BigDecimal bsClosing = bs.totalEquity().current();
        BigDecimal sumOpening = rows.stream().map(EquityMovementRowDto::opening)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rolled = rows.stream().map(EquityMovementRowDto::rolledForward)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ReconciliationDto recon = ReconciliationDto.of(
                "Opening + movements == Balance Sheet equity at period end",
                AmountPairDto.of(rolled, sumOpening),
                AmountPairDto.of(bsClosing, bsOpening));
        ReconciliationDto transfersCheck = ReconciliationDto.of(
                "Transfers between equity components net to zero",
                AmountPairDto.of(totals.transfers(), BigDecimal.ZERO),
                AmountPairDto.zero());

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, companyName, currency,
                from + " – " + to,
                "Opening as at " + openingDate,
                from, to, null,
                Instant.now(), scope.branchUid(), scope.label());

        return new ChangesInEquityDto(header, company, rows, totals,
                bsOpening, bsClosing, profit, recon, transfersCheck);
    }

    // -------------------------------------------------------------------------

    private static EquityMovementRowDto row(StatementLineDto line, boolean fold,
                                            BigDecimal opening, BigDecimal profit,
                                            BigDecimal openingBal, BigDecimal capital,
                                            BigDecimal drawings, BigDecimal transfers,
                                            BigDecimal closing) {
        BigDecimal rolled = opening.add(profit).add(openingBal).add(capital).add(drawings).add(transfers);
        return new EquityMovementRowDto(line.accountId(), line.accountUid(), line.accountCode(),
                line.accountName(), fold, opening, profit, openingBal, capital, drawings, transfers,
                closing, rolled.compareTo(closing) == 0);
    }

    private static EquityMovementRowDto totalsOf(List<EquityMovementRowDto> rows) {
        BigDecimal opening = BigDecimal.ZERO, profit = BigDecimal.ZERO, openingBal = BigDecimal.ZERO,
                capital = BigDecimal.ZERO, drawings = BigDecimal.ZERO, transfers = BigDecimal.ZERO,
                closing = BigDecimal.ZERO;
        boolean allTie = true;
        for (EquityMovementRowDto r : rows) {
            opening    = opening.add(r.opening());
            profit     = profit.add(r.profitForPeriod());
            openingBal = openingBal.add(r.openingBalancesPosted());
            capital    = capital.add(r.capitalIntroduced());
            drawings   = drawings.add(r.drawingsAndDividends());
            transfers  = transfers.add(r.transfers());
            closing    = closing.add(r.closing());
            allTie     = allTie && r.ties();
        }
        return new EquityMovementRowDto(null, null, null, "Total equity", false,
                opening, profit, openingBal, capital, drawings, transfers, closing, allTie);
    }
}
