package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves companyId + optional cashBankAccountUid → the linked GL account for the cash leg
 * (ADR-0016 D-8, BR-CASH-09). Called by AR and AP services to reroute the cash leg from the
 * bare gl_configs.CASH lookup to the chosen (or default) account's linked GL account.
 *
 * <p>Fail-loud: if uid given and inactive, or if null uid and no company default, throws rather
 * than silently posting to the wrong account (mirrors GL BR-GL-10 / FR-CASH-07).
 */
@Component
public class CashBankAccountResolver {

    private final CashBankAccountRepository cashAccounts;
    private final ChartOfAccountRepository  glAccounts;

    public CashBankAccountResolver(CashBankAccountRepository cashAccounts,
                                    ChartOfAccountRepository glAccounts) {
        this.cashAccounts = cashAccounts;
        this.glAccounts   = glAccounts;
    }

    /**
     * Resolves companyId + optional cashBankAccountUid to the linked GL account.
     * If uid is null → use the company default (BR-CASH-09).
     * If no account and no default → throws (never silent null post).
     *
     * @throws IllegalStateException if no usable active account is found
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashAccountGlResolutionDto resolve(Long companyId, String cashBankAccountUid) {
        CashBankAccount account;
        if (cashBankAccountUid != null && !cashBankAccountUid.isBlank()) {
            account = cashAccounts.findByCompanyIdAndUid(companyId, cashBankAccountUid)
                    .orElseThrow(() -> new NotFoundException(
                            "Cash/bank account not found: " + cashBankAccountUid));
            if (!account.isActive()) {
                throw new IllegalStateException(
                        "Cash/bank account " + cashBankAccountUid
                                + " is inactive; cannot be used for settlement (FR-CASH-07, BR-CASH-08).");
            }
        } else {
            account = cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No cash/bank account specified and no company default exists for company "
                                    + companyId + " (BR-CASH-09). Set a default account via CASH.ACCOUNT.MANAGE."));
            if (!account.isActive()) {
                throw new IllegalStateException(
                        "Company default cash/bank account is inactive for company " + companyId
                                + "; cannot post settlement (FR-CASH-07, BR-CASH-08).");
            }
        }

        ChartOfAccount glAcct = glAccounts.findById(account.getGlAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cash/bank account " + account.getUid()
                                + " references gl_account_id=" + account.getGlAccountId()
                                + " which does not exist."));

        if (!glAcct.isActive()) {
            throw new IllegalStateException(
                    "Linked GL account " + glAcct.getAccountCode()
                            + " for cash/bank account " + account.getUid()
                            + " is inactive (BR-GL-10).");
        }

        return new CashAccountGlResolutionDto(
                account.getId(), account.getUid(),
                glAcct.getId(), glAcct.getAccountCode());
    }
}
