package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.cashbank.domain.dto.CashAccountStatementDto;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries for cash account balance and transaction statement (FR-CASH-12).
 * Not an "application service" in the command sense — no writes, no audit.
 */
@Service
@Transactional(readOnly = true)
public class CashAccountStatementQuery {

    private final CashBankAccountRepository accounts;
    private final CashTransactionRepository txns;
    private final ScopeGuard                scopeGuard;

    public CashAccountStatementQuery(CashBankAccountRepository accounts,
                                      CashTransactionRepository txns,
                                      ScopeGuard scopeGuard) {
        this.accounts   = accounts;
        this.txns       = txns;
        this.scopeGuard = scopeGuard;
    }

    /** Current book balance of a single account (FR-CASH-12). */
    public CashAccountBalanceDto getBalance(String accountUid) {
        CashBankAccount account = Lookups.orNotFound(
                accounts.findByUid(accountUid), "CashBankAccount", accountUid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        BigDecimal balance = txns.bookBalance(account.getId());
        if (balance == null) balance = BigDecimal.ZERO;

        return new CashAccountBalanceDto(
                account.getId(), account.getUid(), account.getCode(),
                account.getName(), balance, account.getCurrency().value());
    }

    /** All transactions for an account with the running current balance (FR-CASH-12). */
    public CashAccountStatementDto getStatement(String accountUid) {
        CashBankAccount account = Lookups.orNotFound(
                accounts.findByUid(accountUid), "CashBankAccount", accountUid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        BigDecimal balance = txns.bookBalance(account.getId());
        if (balance == null) balance = BigDecimal.ZERO;

        List<com.erp.modules.cashbank.domain.dto.CashTransactionDto> rows =
                txns.findByCashBankAccountIdOrderByTxnDateAscIdAsc(account.getId())
                        .stream()
                        .map(CashDirectEntryServiceImpl::toDto)
                        .toList();

        return new CashAccountStatementDto(
                account.getId(), account.getUid(), account.getName(),
                balance, account.getCurrency().value(), rows);
    }

    /** Book balances of all company accounts (FR-CASH-12). */
    public List<CashAccountBalanceDto> listBalances(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return accounts.findByCompanyId(companyId).stream().map(account -> {
            BigDecimal balance = txns.bookBalance(account.getId());
            if (balance == null) balance = BigDecimal.ZERO;
            return new CashAccountBalanceDto(
                    account.getId(), account.getUid(), account.getCode(),
                    account.getName(), balance, account.getCurrency().value());
        }).toList();
    }
}
