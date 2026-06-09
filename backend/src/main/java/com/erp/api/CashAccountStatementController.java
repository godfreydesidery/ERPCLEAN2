package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.cashbank.domain.dto.CashAccountStatementDto;
import com.erp.modules.cashbank.domain.dto.CashGlReconciliationDto;
import com.erp.modules.cashbank.service.CashAccountStatementQuery;
import com.erp.modules.cashbank.service.CashGlReconciliationQuery;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only cash account balance, statement, and GL reconciliation views
 * (ADR-0016 D-9, FR-CASH-12/17).
 * Permission: CASH.VIEW.
 */
@RestController
@RequestMapping("/api/v1/cash/statements")
public class CashAccountStatementController {

    private final CashAccountStatementQuery statementQuery;
    private final CashGlReconciliationQuery glReconciliationQuery;

    public CashAccountStatementController(CashAccountStatementQuery statementQuery,
                                           CashGlReconciliationQuery glReconciliationQuery) {
        this.statementQuery       = statementQuery;
        this.glReconciliationQuery = glReconciliationQuery;
    }

    /** Current book balance for a single account (FR-CASH-12). */
    @GetMapping("/accounts/uid/{uid}/balance")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashAccountBalanceDto getBalance(@PathVariable String uid) {
        return statementQuery.getBalance(uid);
    }

    /** Full transaction statement for a single account (FR-CASH-12). */
    @GetMapping("/accounts/uid/{uid}/statement")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashAccountStatementDto getStatement(@PathVariable String uid) {
        return statementQuery.getStatement(uid);
    }

    /** Book balances for all accounts of a company (FR-CASH-12). */
    @GetMapping("/balances")
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashAccountBalanceDto> listBalances(@RequestParam Long companyId) {
        return statementQuery.listBalances(companyId);
    }

    /** Book vs GL balance for all accounts of a company (ADR-0016 D-9, FR-CASH-17). */
    @GetMapping("/gl-reconciliation")
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashGlReconciliationDto> glReconciliation(@RequestParam Long companyId) {
        return glReconciliationQuery.reconcileAll(companyId);
    }

    /** Book vs GL balance for a single account (FR-CASH-17). */
    @GetMapping("/accounts/uid/{uid}/gl-reconciliation")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashGlReconciliationDto glReconciliationOne(@PathVariable String uid) {
        return glReconciliationQuery.reconcileOne(uid);
    }
}
