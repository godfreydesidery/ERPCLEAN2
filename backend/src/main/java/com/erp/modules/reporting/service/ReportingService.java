package com.erp.modules.reporting.service;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import java.time.LocalDate;

/**
 * Orchestrating read-only service for all financial statements (ADR-0018 D-1).
 * assertCanActIn is called first on every method (D-11, NFR-REP-01).
 * Reporting posts nothing and owns no business table (BR-REP-08).
 */
public interface ReportingService {

    /**
     * Income Statement / P&amp;L for [fromDate, toDate] with comparative [cmpFrom, cmpTo].
     * Pass null cmpFrom/cmpTo to use the default comparative (prior period of equal length, D-8).
     */
    IncomeStatementDto incomeStatement(Long companyId,
                                        LocalDate fromDate, LocalDate toDate,
                                        LocalDate cmpFrom,  LocalDate cmpTo);

    /**
     * Balance Sheet as-at asAtDate with comparative as-at compareAsAt.
     * Pass null compareAsAt to use the default (fromDate − 1, D-8).
     */
    BalanceSheetDto balanceSheet(Long companyId,
                                  LocalDate asAtDate,
                                  LocalDate compareAsAt);

    /**
     * Cash-Flow Statement (indirect) for [fromDate, toDate] with comparative [cmpFrom, cmpTo].
     * Pass null cmpFrom/cmpTo to use the default comparative (D-8).
     */
    CashFlowStatementDto cashFlow(Long companyId,
                                   LocalDate fromDate, LocalDate toDate,
                                   LocalDate cmpFrom,  LocalDate cmpTo);

    /**
     * Paginated GL account ledger for one account over a period (D-3(d)).
     * accountUid resolves to an account that must belong to companyId.
     */
    AccountLedgerDto accountLedger(Long companyId,
                                    String accountUid,
                                    LocalDate fromDate, LocalDate toDate,
                                    int page, int size);
}
