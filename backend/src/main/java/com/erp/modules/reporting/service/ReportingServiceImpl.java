package com.erp.modules.reporting.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.ChangesInEquityDto;
import com.erp.modules.reporting.domain.dto.FinancialRatiosDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates financial statement assembly (ADR-0018 D-1, D-11).
 *
 * <p>assertCanActIn is the FIRST call on every public method — before any data access.
 * A branch filter is resolved only after it, together with the company, and then passes
 * {@code BranchReadGuard} ({@link StatementScopeResolver}).
 * This class delegates all SQL work to the builder/query components.
 */
@Service
@Transactional(readOnly = true)
public class ReportingServiceImpl implements ReportingService {

    private final ScopeGuard               scopeGuard;
    private final CompanyRepository        companyRepo;
    private final ComparativeWindowResolver comparativeResolver;
    private final IncomeStatementBuilder   plBuilder;
    private final BalanceSheetBuilder      bsBuilder;
    private final CashFlowStatementBuilder cfBuilder;
    private final ChangesInEquityBuilder   soceBuilder;
    private final FinancialRatiosBuilder   ratiosBuilder;
    private final AccountLedgerQuery       ledgerQuery;
    private final StatementScopeResolver   scopeResolver;
    private final ReportCompanyHeaderQuery companyHeaderQuery;

    public ReportingServiceImpl(ScopeGuard scopeGuard,
                                 CompanyRepository companyRepo,
                                 ComparativeWindowResolver comparativeResolver,
                                 IncomeStatementBuilder plBuilder,
                                 BalanceSheetBuilder bsBuilder,
                                 CashFlowStatementBuilder cfBuilder,
                                 ChangesInEquityBuilder soceBuilder,
                                 FinancialRatiosBuilder ratiosBuilder,
                                 AccountLedgerQuery ledgerQuery,
                                 StatementScopeResolver scopeResolver,
                                 ReportCompanyHeaderQuery companyHeaderQuery) {
        this.scopeGuard          = scopeGuard;
        this.companyRepo         = companyRepo;
        this.comparativeResolver = comparativeResolver;
        this.plBuilder           = plBuilder;
        this.bsBuilder           = bsBuilder;
        this.cfBuilder           = cfBuilder;
        this.soceBuilder         = soceBuilder;
        this.ratiosBuilder       = ratiosBuilder;
        this.ledgerQuery         = ledgerQuery;
        this.scopeResolver       = scopeResolver;
        this.companyHeaderQuery  = companyHeaderQuery;
    }

    @Override
    public IncomeStatementDto incomeStatement(Long companyId,
                                               LocalDate fromDate, LocalDate toDate,
                                               LocalDate cmpFrom,  LocalDate cmpTo,
                                               String branchUid, boolean unassigned) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        requireOrderedRange(fromDate, toDate);
        StatementScope scope = scopeResolver.resolve(companyId, branchUid, unassigned);
        LocalDate effectiveCmpFrom = cmpFrom != null ? cmpFrom : comparativeResolver.comparativeFrom(fromDate, toDate);
        LocalDate effectiveCmpTo   = cmpTo   != null ? cmpTo   : comparativeResolver.comparativeTo(fromDate);
        String companyName = resolveCompanyName(companyId);
        return plBuilder.build(companyId, companyName, "TZS", scope,
                fromDate, toDate, effectiveCmpFrom, effectiveCmpTo);
    }

    @Override
    public BalanceSheetDto balanceSheet(Long companyId,
                                         LocalDate asAtDate,
                                         LocalDate compareAsAt,
                                         String branchUid, boolean unassigned) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        StatementScope scope = scopeResolver.resolve(companyId, branchUid, unassigned);
        LocalDate effectiveCompareAsAt = compareAsAt != null
                ? compareAsAt
                : comparativeResolver.comparativeAsAt(asAtDate);
        String companyName = resolveCompanyName(companyId);
        return bsBuilder.build(companyId, companyName, "TZS", scope, asAtDate, effectiveCompareAsAt);
    }

    @Override
    public CashFlowStatementDto cashFlow(Long companyId,
                                          LocalDate fromDate, LocalDate toDate,
                                          LocalDate cmpFrom,  LocalDate cmpTo,
                                          String branchUid, boolean unassigned) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        StatementScope scope = scopeResolver.resolve(companyId, branchUid, unassigned);
        LocalDate effectiveCmpFrom = cmpFrom != null ? cmpFrom : comparativeResolver.comparativeFrom(fromDate, toDate);
        LocalDate effectiveCmpTo   = cmpTo   != null ? cmpTo   : comparativeResolver.comparativeTo(fromDate);
        String companyName = resolveCompanyName(companyId);
        return cfBuilder.build(companyId, companyName, "TZS", scope,
                fromDate, toDate, effectiveCmpFrom, effectiveCmpTo);
    }

    @Override
    public ChangesInEquityDto changesInEquity(Long companyId, LocalDate fromDate, LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        requireOrderedRange(fromDate, toDate);
        String companyName = resolveCompanyName(companyId);
        return soceBuilder.build(companyId, companyName, "TZS",
                companyHeaderQuery.forCompany(companyId), fromDate, toDate);
    }

    @Override
    public FinancialRatiosDto financialRatios(Long companyId, LocalDate fromDate, LocalDate toDate,
                                               String branchUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        requireOrderedRange(fromDate, toDate);
        StatementScope scope = scopeResolver.resolve(companyId, branchUid, false);
        String companyName = resolveCompanyName(companyId);
        return ratiosBuilder.build(companyId, companyName, "TZS",
                companyHeaderQuery.forCompany(companyId), scope, fromDate, toDate,
                comparativeResolver.comparativeFrom(fromDate, toDate),
                comparativeResolver.comparativeTo(fromDate));
    }

    @Override
    public AccountLedgerDto accountLedger(Long companyId,
                                           String accountUid,
                                           LocalDate fromDate, LocalDate toDate,
                                           int page, int size) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ledgerQuery.query(companyId, accountUid, fromDate, toDate, page, size);
    }

    // -------------------------------------------------------------------------

    private static void requireOrderedRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must not be after toDate.");
        }
    }

    private String resolveCompanyName(Long companyId) {
        return companyRepo.findById(companyId)
                .map(com.erp.modules.iam.domain.entity.Company::getName)
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
    }
}
