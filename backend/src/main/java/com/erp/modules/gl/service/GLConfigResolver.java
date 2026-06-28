package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a posting role (GlConfigKey) to an active ChartOfAccount for a company
 * (ADR-0013 D-5, BR-GL-10). Called by GLPostingService and the event handlers.
 *
 * <p>A missing mapping or an inactive mapped account throws — the handler fails the event
 * and the outbox retries/parks (no silent post to a null/wrong account, BR-GL-10).
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
     * @throws IllegalStateException if the mapping is missing or the account is inactive (BR-GL-10)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChartOfAccount resolve(Long companyId, GlConfigKey key) {
        GlConfig config = configs.findByCompanyIdAndConfigKey(companyId, key)
                .orElseThrow(() -> new IllegalStateException(
                        // BR-GL-10: posting role mapping must be configured before posting can proceed
                        "No GL account is mapped for the posting role '" + key
                                + "'. Please configure the account mapping under General Ledger settings"
                                + " before posting can proceed."));

        ChartOfAccount account = accounts.findById(config.getAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        // BR-GL-10: mapped account no longer exists
                        "The account mapped to the posting role '" + key
                                + "' no longer exists. Please update the GL account mapping."));

        if (!account.isActive()) {
            // BR-GL-10: mapped account must be active
            throw new IllegalStateException(
                    "The account '" + account.getAccountCode()
                            + "' mapped to the posting role '" + key
                            + "' is inactive. Please activate the account or update the GL account mapping.");
        }
        return account;
    }
}
