package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Year-End Close orchestration service (ADR-0019 D-1/D-2/D-6).
 *
 * <p>Drives the existing GL primitives — no new posting path or bypass:
 * <ul>
 *   <li>closeFiscalYear: guards → P&L balance read → build closing JournalEntryDraft →
 *       GLPostingService.post (period still OPEN, D-5) → closePeriod ×N → mark CLOSED → audit.
 *   <li>reopenFiscalYear: guards → reopenPeriod ×N → GLPostingService.postReversal (period now
 *       OPEN, D-6) → mark OPEN, clear stamps → audit.
 * </ul>
 */
@Service
@Transactional
public class YearEndCloseServiceImpl implements YearEndCloseService {

    private final GLPostingService          glPostingService;
    private final GLConfigResolver          glConfigResolver;
    private final FiscalCalendarService     fiscalCalendarService;
    private final FiscalYearRepository      years;
    private final FiscalPeriodRepository    periods;
    private final ChartOfAccountRepository  accounts;
    private final JournalLineRepository     journalLines;
    private final JournalEntryRepository    journalEntries;
    private final CompanyRepository         companies;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public YearEndCloseServiceImpl(GLPostingService glPostingService,
                                    GLConfigResolver glConfigResolver,
                                    FiscalCalendarService fiscalCalendarService,
                                    FiscalYearRepository years,
                                    FiscalPeriodRepository periods,
                                    ChartOfAccountRepository accounts,
                                    JournalLineRepository journalLines,
                                    JournalEntryRepository journalEntries,
                                    CompanyRepository companies,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.glPostingService    = glPostingService;
        this.glConfigResolver    = glConfigResolver;
        this.fiscalCalendarService = fiscalCalendarService;
        this.years               = years;
        this.periods             = periods;
        this.accounts            = accounts;
        this.journalLines        = journalLines;
        this.journalEntries      = journalEntries;
        this.companies           = companies;
        this.scopeGuard          = scopeGuard;
        this.audit               = audit;
    }

    // =========================================================================
    // closeFiscalYear
    // =========================================================================

    @Override
    public FiscalYearDto closeFiscalYear(String fiscalYearUid) {
        // 1. Resolve + scope
        FiscalYear year = requireYear(fiscalYearUid);
        scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId());

        // 2. Guards (D-8) — reject before any write
        if (year.getStatus() == PeriodStatus.CLOSED) {
            throw new ConflictException("This fiscal year is already CLOSED."
                    + " Reopen it first if a correction is needed.");
        }
        List<FiscalPeriod> yearPeriods = periods.findByFiscalYearIdOrderByPeriodNo(year.getId());
        if (yearPeriods.isEmpty()) {
            throw new ConflictException("This fiscal year has no periods."
                    + " Seed the fiscal calendar first.");
        }
        checkPriorYearClosed(year);

        // 3. Compute each P&L account's net movement over [startDate, endDate]
        List<ChartOfAccount> plAccounts = accounts.findByCompanyIdAndAccountTypeIn(
                year.getCompanyId(), List.of(AccountType.INCOME, AccountType.EXPENSE));

        // Build a map accountId → [sumDebit, sumCredit] from the windowed aggregate
        Map<Long, BigDecimal[]> movementMap = buildMovementMap(
                year.getCompanyId(), year.getStartDate(), year.getEndDate());

        // 4. Construct the closing JournalEntryDraft (D-4)
        String baseCurrency = resolveBaseCurrency(year.getCompanyId());
        Long actorId = actorId();

        List<LineDraft> draftLines = new ArrayList<>();
        BigDecimal sumNetDebits = BigDecimal.ZERO; // Σ netDebit across all P&L accounts

        for (ChartOfAccount acct : plAccounts) {
            BigDecimal[] sums = movementMap.get(acct.getId());
            if (sums == null) {
                continue; // no movement — skip (OQ-CLOSE-05)
            }
            BigDecimal sumDebit  = sums[0] != null ? sums[0] : BigDecimal.ZERO;
            BigDecimal sumCredit = sums[1] != null ? sums[1] : BigDecimal.ZERO;

            // netDebit = sumDebit − sumCredit (debit-positive formulation, D-3)
            BigDecimal netDebit = sumDebit.subtract(sumCredit);
            if (netDebit.compareTo(BigDecimal.ZERO) == 0) {
                continue; // zero-net account — no line (OQ-CLOSE-05)
            }

            // Zeroing line: negate netDebit (D-3/D-4)
            if (netDebit.compareTo(BigDecimal.ZERO) > 0) {
                // Account is net-debit — zeroing line is a CREDIT
                draftLines.add(new LineDraft(acct.getId(), null, netDebit, baseCurrency,
                        "Year-end close — zero " + acct.getAccountCode()));
            } else {
                // Account is net-credit (negative netDebit) — zeroing line is a DEBIT
                draftLines.add(new LineDraft(acct.getId(), netDebit.negate(), null, baseCurrency,
                        "Year-end close — zero " + acct.getAccountCode()));
            }
            sumNetDebits = sumNetDebits.add(netDebit);
        }

        // Resolve 3900 Retained Earnings (D-9) — throws if missing/inactive (BR-CLOSE-11)
        ChartOfAccount retainedEarnings = glConfigResolver.resolve(
                year.getCompanyId(), GlConfigKey.RETAINED_EARNINGS);

        // netProfit = −Σ(netDebit across P&L accounts)
        BigDecimal netProfit = sumNetDebits.negate();

        // 5. Post the closing entry FIRST (period still OPEN — D-5 ordering)
        JournalEntryDto closing = null;
        if (!draftLines.isEmpty() || netProfit.compareTo(BigDecimal.ZERO) != 0) {
            // Add the 3900 balancing line (D-4)
            if (netProfit.compareTo(BigDecimal.ZERO) > 0) {
                // Net profit → CR 3900
                draftLines.add(new LineDraft(retainedEarnings.getId(), null, netProfit,
                        baseCurrency, "Year-end close — retained earnings (net profit)"));
            } else if (netProfit.compareTo(BigDecimal.ZERO) < 0) {
                // Net loss → DR 3900
                draftLines.add(new LineDraft(retainedEarnings.getId(), netProfit.negate(), null,
                        baseCurrency, "Year-end close — retained earnings (net loss)"));
            }
            // else exactly break-even and no P&L lines: nothing to post

            if (draftLines.size() >= 2) {
                JournalEntryDraft closingDraft = new JournalEntryDraft(
                        year.getCompanyId(),
                        null,                         // branchId null — company-level journal
                        year.getEndDate(),
                        "Year-end close " + year.getYearCode(),
                        JournalSourceType.YEAR_END_CLOSE,
                        year.getUid(),
                        null,                         // not a reversal
                        actorId,
                        draftLines);
                closing = glPostingService.post(closingDraft);
            }
        }
        // If draftLines < 2 (all-zero year), no journal posted; closing = null (D-4 edge)

        // 6. Auto-close every still-OPEN period (BR-CLOSE-06)
        for (FiscalPeriod period : yearPeriods) {
            if (period.getStatus() == PeriodStatus.OPEN) {
                fiscalCalendarService.closePeriod(period.getUid());
            }
        }

        // 7. Mark the fiscal year CLOSED
        year.setStatus(PeriodStatus.CLOSED);
        year.setClosedAt(Instant.now());
        year.setClosedBy(actorId);
        year.setClosingJournalUid(closing != null ? closing.uid() : null);
        year.setUpdatedAt(Instant.now());
        year.setUpdatedBy(actorId);
        years.save(year);

        // 8. Audit
        String journalUid = closing != null ? closing.uid() : "none";
        audit.record(AuditEvent.of(AuditActions.GL_YEAR_CLOSE, "fiscal_years",
                        year.getId(), year.getUid())
                .detail(Map.of(
                        "yearCode",          year.getYearCode(),
                        "closingJournalUid", journalUid,
                        "netRolled",         netProfit.toPlainString())));

        // 9. Return
        return toYearDto(year);
    }

    // =========================================================================
    // reopenFiscalYear
    // =========================================================================

    @Override
    public FiscalYearDto reopenFiscalYear(String fiscalYearUid) {
        // 1. Resolve + scope
        FiscalYear year = requireYear(fiscalYearUid);
        scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId());

        // 2. Guards (D-8)
        if (year.getStatus() != PeriodStatus.CLOSED) {
            throw new ConflictException("This fiscal year is not CLOSED"
                    + " — only a CLOSED year may be reopened.");
        }
        checkMostRecentlyClosed(year);

        // 3. Reopen the year's periods FIRST (so the reversal posts into an OPEN period — D-6)
        List<FiscalPeriod> yearPeriods = periods.findByFiscalYearIdOrderByPeriodNo(year.getId());
        for (FiscalPeriod period : yearPeriods) {
            if (period.getStatus() == PeriodStatus.CLOSED) {
                fiscalCalendarService.reopenPeriod(period.getUid());
            }
        }

        // 4. Post the closing-journal reversal (append-only — BR-CLOSE-07/08)
        Long actorId = actorId();
        JournalEntryDto reversal = null;
        String priorClosingJournalUid = year.getClosingJournalUid();

        if (priorClosingJournalUid != null) {
            // Idempotency guard: the closing entry must not already be reversed
            JournalEntry closingEntry = journalEntries.findByUid(priorClosingJournalUid)
                    .orElseThrow(() -> new NotFoundException("Closing journal not found."));
            if (journalEntries.existsByReversalOfId(closingEntry.getId())) {
                throw new ConflictException("The closing journal has already been reversed"
                        + " — this year may not be reopened again without a fresh close.");
            }
            reversal = glPostingService.postReversal(
                    priorClosingJournalUid,
                    year.getEndDate(),
                    JournalSourceType.YEAR_END_CLOSE,
                    year.getUid(),
                    actorId);
        }
        // If closingJournalUid == null (no-trading year, D-4 edge): no journal to reverse; skip.

        // 5. Flip the year OPEN + clear the close stamps (D-6 step 5); record reopen audit (P2-M1)
        year.setStatus(PeriodStatus.OPEN);
        year.setClosedAt(null);
        year.setClosedBy(null);
        year.setClosingJournalUid(null);
        year.setReopenedAt(Instant.now());
        year.setReopenedBy(actorId);
        year.setUpdatedAt(Instant.now());
        year.setUpdatedBy(actorId);
        years.save(year);

        // 6. Audit
        String reversalUid = reversal != null ? reversal.uid() : "none";
        audit.record(AuditEvent.of(AuditActions.GL_YEAR_CLOSE, "fiscal_years",
                        year.getId(), year.getUid())
                .detail(Map.of(
                        "action",                    "reopen",
                        "reversalJournalUid",        reversalUid,
                        "reversedClosingJournalUid", priorClosingJournalUid != null
                                ? priorClosingJournalUid : "none")));

        // 7. Return
        return toYearDto(year);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** BR-CLOSE-04: immediately prior fiscal year (by startDate) must be CLOSED or absent. */
    private void checkPriorYearClosed(FiscalYear year) {
        List<FiscalYear> allYears = years.findByCompanyIdOrderByStartDateDesc(year.getCompanyId());
        FiscalYear priorYear = null;
        for (FiscalYear fy : allYears) {
            if (fy.getStartDate().isBefore(year.getStartDate())) {
                priorYear = fy;
                break; // first one with startDate < this year's startDate (ordered desc)
            }
        }
        if (priorYear != null && priorYear.getStatus() != PeriodStatus.CLOSED) {
            throw new ConflictException(
                    "The immediately prior fiscal year (" + priorYear.getYearCode()
                            + ") must be CLOSED before closing " + year.getYearCode()
                            + " (BR-CLOSE-04).");
        }
    }

    /** BR-CLOSE-10: only the most-recently-closed year may be reopened. */
    private void checkMostRecentlyClosed(FiscalYear year) {
        List<FiscalYear> allYears = years.findByCompanyIdOrderByStartDateDesc(year.getCompanyId());
        for (FiscalYear fy : allYears) {
            if (fy.getId().equals(year.getId())) {
                break; // reached this year — no later CLOSED year found
            }
            if (fy.getStatus() == PeriodStatus.CLOSED) {
                throw new ConflictException(
                        "Only the most-recently-closed fiscal year may be reopened. "
                                + "Year " + fy.getYearCode() + " is CLOSED and starts later "
                                + "than " + year.getYearCode() + " — reopen it first (BR-CLOSE-10).");
            }
        }
    }

    /**
     * Builds accountId → [sumDebit, sumCredit] from the windowed P&L aggregate (D-3).
     * Rows with null sums (no movement) are omitted by the GROUP BY; callers treat absent = zero.
     */
    private Map<Long, BigDecimal[]> buildMovementMap(Long companyId,
                                                      java.time.LocalDate from,
                                                      java.time.LocalDate to) {
        List<Object[]> rows = journalLines.periodMovementByAccount(companyId, from, to);
        Map<Long, BigDecimal[]> map = new HashMap<>();
        for (Object[] row : rows) {
            Long accountId   = ((Number) row[0]).longValue();
            BigDecimal debit  = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            BigDecimal credit = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            map.put(accountId, new BigDecimal[]{debit, credit});
        }
        return map;
    }

    private String resolveBaseCurrency(Long companyId) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found."));
        return company.getBaseCurrency();
    }

    private FiscalYear requireYear(String uid) {
        return years.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FiscalYear", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private FiscalYearDto toYearDto(FiscalYear y) {
        return new FiscalYearDto(
                y.getId(), y.getUid(), y.getCompanyId(),
                y.getYearCode(), y.getStartMonth(), y.getStartDate(), y.getEndDate(),
                y.getStatus(),
                y.getClosedAt(), y.getClosedBy(), y.getClosingJournalUid(),
                y.getReopenedAt(), y.getReopenedBy());
    }
}
