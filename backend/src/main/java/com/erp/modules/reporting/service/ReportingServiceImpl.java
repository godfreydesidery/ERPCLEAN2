package com.erp.modules.reporting.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
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
    private final AccountLedgerQuery       ledgerQuery;

    public ReportingServiceImpl(ScopeGuard scopeGuard,
                                 CompanyRepository companyRepo,
                                 ComparativeWindowResolver comparativeResolver,
                                 IncomeStatementBuilder plBuilder,
                                 BalanceSheetBuilder bsBuilder,
                                 CashFlowStatementBuilder cfBuilder,
                                 AccountLedgerQuery ledgerQuery) {
        this.scopeGuard          = scopeGuard;
        this.companyRepo         = companyRepo;
        this.comparativeResolver = comparativeResolver;
        this.plBuilder           = plBuilder;
        this.bsBuilder           = bsBuilder;
        this.cfBuilder           = cfBuilder;
        this.ledgerQuery         = ledgerQuery;
    }

    @Override
    public IncomeStatementDto incomeStatement(Long companyId,
                                               LocalDate fromDate, LocalDate toDate,
                                               LocalDate cmpFrom,  LocalDate cmpTo) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must not be after toDate.");
        }
        LocalDate effectiveCmpFrom = cmpFrom != null ? cmpFrom : comparativeResolver.comparativeFrom(fromDate, toDate);
        LocalDate effectiveCmpTo   = cmpTo   != null ? cmpTo   : comparativeResolver.comparativeTo(fromDate);
        String companyName = resolveCompanyName(companyId);
        return plBuilder.build(companyId, companyName, "TZS",
                fromDate, toDate, effectiveCmpFrom, effectiveCmpTo);
    }

    @Override
    public BalanceSheetDto balanceSheet(Long companyId,
                                         LocalDate asAtDate,
                                         LocalDate compareAsAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        LocalDate effectiveCompareAsAt = compareAsAt != null
                ? compareAsAt
                : comparativeResolver.comparativeAsAt(asAtDate);
        String companyName = resolveCompanyName(companyId);
        return bsBuilder.build(companyId, companyName, "TZS", asAtDate, effectiveCompareAsAt);
    }

    @Override
    public CashFlowStatementDto cashFlow(Long companyId,
                                          LocalDate fromDate, LocalDate toDate,
                                          LocalDate cmpFrom,  LocalDate cmpTo) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        LocalDate effectiveCmpFrom = cmpFrom != null ? cmpFrom : comparativeResolver.comparativeFrom(fromDate, toDate);
        LocalDate effectiveCmpTo   = cmpTo   != null ? cmpTo   : comparativeResolver.comparativeTo(fromDate);
        String companyName = resolveCompanyName(companyId);
        return cfBuilder.build(companyId, companyName, "TZS",
                fromDate, toDate, effectiveCmpFrom, effectiveCmpTo);
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

    private String resolveCompanyName(Long companyId) {
        return companyRepo.findById(companyId)
                .map(com.erp.modules.iam.domain.entity.Company::getName)
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
    }
}
