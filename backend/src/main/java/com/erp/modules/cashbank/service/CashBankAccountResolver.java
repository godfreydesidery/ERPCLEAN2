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
                    .orElseThrow(() -> NotFoundException.of("Cash/bank account", cashBankAccountUid));
            if (!account.isActive()) {
                // FR-CASH-07, BR-CASH-08: inactive accounts cannot be used for settlement
                throw new IllegalStateException(
                        "The selected cash/bank account is inactive and cannot be used for settlement.");
            }
        } else {
            account = cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId)
                    .orElseThrow(() -> new IllegalStateException(
                            // BR-CASH-09: a default account must be configured before it can be used implicitly
                            "No cash/bank account was specified and no default account has been set for this company. "
                                    + "Please set a default cash/bank account before proceeding."));
            if (!account.isActive()) {
                // FR-CASH-07, BR-CASH-08: inactive accounts cannot be used for settlement
                throw new IllegalStateException(
                        "The company default cash/bank account is currently inactive and cannot be used for settlement. "
                                + "Please activate it or set a different default account.");
            }
        }

        ChartOfAccount glAcct = glAccounts.findById(account.getGlAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        // Data-integrity guard: the linked GL account was deleted after the cash account was set up
                        "The GL account linked to this cash/bank account no longer exists. "
                                + "Please update the cash/bank account configuration."));

        if (!glAcct.isActive()) {
            // BR-GL-10: inactive GL accounts cannot receive postings
            throw new IllegalStateException(
                    "The GL account linked to this cash/bank account ("
                            + glAcct.getAccountCode()
                            + ") is inactive. Please reactivate it or reassign the cash/bank account to an active GL account.");
        }

        return new CashAccountGlResolutionDto(
                account.getId(), account.getUid(),
                glAcct.getId(), glAcct.getAccountCode());
    }
}
