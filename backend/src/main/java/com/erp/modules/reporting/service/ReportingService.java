package com.erp.modules.reporting.service;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.ChangesInEquityDto;
import com.erp.modules.reporting.domain.dto.FinancialRatiosDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import java.time.LocalDate;

/**
 * Orchestrating read-only service for all financial statements (ADR-0018 D-1).
 * assertCanActIn is called first on every method (D-11, NFR-REP-01).
 * Reporting posts nothing and owns no business table (BR-REP-08).
 *
 * <p><b>Branch filter</b> (on the P&amp;L, Balance Sheet, Cash-Flow and ratios): an optional
 * {@code branchUid} narrows the statement to that branch's journal lines. The uid is resolved
 * together with {@code companyId} (a foreign or unknown uid is "not found"), and a caller who is
 * not assigned to the branch is refused ({@code BranchReadGuard}). {@code unassigned=true} reads
 * instead the company-level lines that carry no branch. See {@link StatementScope}.
 */
public interface ReportingService {

    /**
     * Income Statement / P&amp;L for [fromDate, toDate] with comparative [cmpFrom, cmpTo].
     * Pass null cmpFrom/cmpTo to use the default comparative (prior period of equal length, D-8).
     */
    default IncomeStatementDto incomeStatement(Long companyId,
                                                LocalDate fromDate, LocalDate toDate,
                                                LocalDate cmpFrom,  LocalDate cmpTo) {
        return incomeStatement(companyId, fromDate, toDate, cmpFrom, cmpTo, null, false);
    }

    /** Income Statement narrowed to a branch, or to the company-level entries. */
    IncomeStatementDto incomeStatement(Long companyId,
                                        LocalDate fromDate, LocalDate toDate,
                                        LocalDate cmpFrom,  LocalDate cmpTo,
                                        String branchUid, boolean unassigned);

    /**
     * Balance Sheet as-at asAtDate with comparative as-at compareAsAt.
     * Pass null compareAsAt to use the default (fromDate − 1, D-8).
     */
    default BalanceSheetDto balanceSheet(Long companyId,
                                          LocalDate asAtDate,
                                          LocalDate compareAsAt) {
        return balanceSheet(companyId, asAtDate, compareAsAt, null, false);
    }

    /** Balance Sheet narrowed to a branch, or to the company-level entries. */
    BalanceSheetDto balanceSheet(Long companyId,
                                  LocalDate asAtDate,
                                  LocalDate compareAsAt,
                                  String branchUid, boolean unassigned);

    /**
     * Cash-Flow Statement (indirect) for [fromDate, toDate] with comparative [cmpFrom, cmpTo].
     * Pass null cmpFrom/cmpTo to use the default comparative (D-8).
     */
    default CashFlowStatementDto cashFlow(Long companyId,
                                           LocalDate fromDate, LocalDate toDate,
                                           LocalDate cmpFrom,  LocalDate cmpTo) {
        return cashFlow(companyId, fromDate, toDate, cmpFrom, cmpTo, null, false);
    }

    /** Cash-Flow Statement narrowed to a branch, or to the company-level entries. */
    CashFlowStatementDto cashFlow(Long companyId,
                                   LocalDate fromDate, LocalDate toDate,
                                   LocalDate cmpFrom,  LocalDate cmpTo,
                                   String branchUid, boolean unassigned);

    /**
     * Statement of Changes in Equity for [fromDate, toDate], company-wide: per equity component,
     * opening (Balance Sheet as-at fromDate − 1), profit, other movements, closing (Balance Sheet
     * as-at toDate).
     */
    ChangesInEquityDto changesInEquity(Long companyId, LocalDate fromDate, LocalDate toDate);

    /**
     * Financial ratios for [fromDate, toDate], computed from the Income Statement for the period
     * and the Balance Sheet at its opening and closing dates, optionally for one branch.
     */
    FinancialRatiosDto financialRatios(Long companyId, LocalDate fromDate, LocalDate toDate,
                                        String branchUid);

    /**
     * Paginated GL account ledger for one account over a period (D-3(d)).
     * accountUid resolves to an account that must belong to companyId.
     */
    AccountLedgerDto accountLedger(Long companyId,
                                    String accountUid,
                                    LocalDate fromDate, LocalDate toDate,
                                    int page, int size);
}
