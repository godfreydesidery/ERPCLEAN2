package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashGlReconciliationDto;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares the cash-book balance of each cash/bank account with its linked GL account balance.
 * A non-zero difference indicates a bug — this report surfaces it (ADR-0016 D-9, FR-CASH-17).
 */
@Service
@Transactional(readOnly = true)
public class CashGlReconciliationQuery {

    private final CashBankAccountRepository accounts;
    private final CashTransactionRepository txns;
    private final ChartOfAccountRepository  glAccounts;
    private final JournalLineRepository     journalLines;
    private final ScopeGuard                scopeGuard;

    public CashGlReconciliationQuery(CashBankAccountRepository accounts,
                                      CashTransactionRepository txns,
                                      ChartOfAccountRepository glAccounts,
                                      JournalLineRepository journalLines,
                                      ScopeGuard scopeGuard) {
        this.accounts     = accounts;
        this.txns         = txns;
        this.glAccounts   = glAccounts;
        this.journalLines = journalLines;
        this.scopeGuard   = scopeGuard;
    }

    /**
     * GL reconciliation for all cash/bank accounts of a company (FR-CASH-17).
     * Each row: book balance vs linked GL balance and their difference.
     */
    public List<CashGlReconciliationDto> reconcileAll(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return accounts.findByCompanyId(companyId)
                .stream()
                .map(account -> buildRow(companyId, account))
                .toList();
    }

    /**
     * GL reconciliation for a single cash/bank account (FR-CASH-17).
     */
    public CashGlReconciliationDto reconcileOne(String accountUid) {
        CashBankAccount account = Lookups.orNotFound(
                accounts.findByUid(accountUid), "CashBankAccount", accountUid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());
        return buildRow(account.getCompanyId(), account);
    }

    // -------------------------------------------------------------------------

    private CashGlReconciliationDto buildRow(Long companyId, CashBankAccount account) {
        BigDecimal bookBal = txns.bookBalance(account.getId());
        if (bookBal == null) bookBal = BigDecimal.ZERO;

        // GL balance for a 1xxx asset account: debit − credit = net debit = asset value
        BigDecimal glBal = journalLines.accountBalance(companyId, account.getGlAccountId());
        if (glBal == null) glBal = BigDecimal.ZERO;

        BigDecimal diff = bookBal.subtract(glBal);

        ChartOfAccount coa = glAccounts.findById(account.getGlAccountId()).orElse(null);
        String glCode = coa != null ? coa.getAccountCode() : "?";

        return new CashGlReconciliationDto(
                account.getUid(), account.getName(),
                account.getGlAccountId(), glCode,
                bookBal, glBal, diff);
    }
}
