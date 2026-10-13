package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.platform.common.api.AccountingSetupException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a posting role (GlConfigKey) to an active ChartOfAccount for a company
 * (ADR-0013 D-5, BR-GL-10). Called by GLPostingService and the event handlers.
 *
 * <p>A missing mapping or an inactive mapped account throws {@link AccountingSetupException} — no
 * silent post to a null/wrong account (BR-GL-10). The exception type lets the API show operators a
 * plain sentence while accountants see which posting role needs mapping (ACC-21).
 */
@Component
public class GLConfigResolver {

    private final GlConfigRepository configs;
    private final ChartOfAccountRepository accounts;

    public GLConfigResolver(GlConfigRepository configs, ChartOfAccountRepository accounts) {
        this.configs = configs;
        this.accounts = accounts;
    }

    /**
     * Resolves the active account for a required posting role.
     *
     * @throws AccountingSetupException if the mapping is missing or the account is inactive
     *         (BR-GL-10). Its message is the accountant's version; an operator calling from a
     *         non-accounting screen is shown a plain "ask your accountant" sentence instead (ACC-21).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChartOfAccount resolve(Long companyId, GlConfigKey key) {
        // The posting role must be mapped to an existing, active account before posting.
        GlConfig config = configs.findByCompanyIdAndConfigKey(companyId, key)
                .orElseThrow(() -> new AccountingSetupException(
                        "No account is mapped for " + describe(key) + " postings. Map an account"
                                + " to it under General Ledger, Posting Accounts, then try again."));

        ChartOfAccount account = accounts.findById(config.getAccountId())
                .orElseThrow(() -> new AccountingSetupException(
                        "The account mapped for " + describe(key) + " postings no longer exists."
                                + " Choose another account under General Ledger, Posting Accounts."));

        if (!account.isActive()) {
            throw new AccountingSetupException(
                    "Account " + account.getAccountCode() + " is mapped for " + describe(key)
                            + " postings but is inactive. Activate it, or map an active account"
                            + " under General Ledger, Posting Accounts.");
        }
        return account;
    }

    /**
     * The posting role as an accountant reads it: "Stock Adjustment (STOCK_ADJUSTMENT)". The key is
     * kept in brackets because that is what the Posting Accounts screen shows.
     */
    static String describe(GlConfigKey key) {
        StringBuilder label = new StringBuilder();
        for (String word : key.name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label + " (" + key.name() + ")";
    }
}
