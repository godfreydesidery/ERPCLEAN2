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
                        "gl_configs mapping missing for key " + key
                                + " in company " + companyId
                                + " — set it via GL.MANAGE before auto-posting (BR-GL-10)."));

        ChartOfAccount account = accounts.findById(config.getAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "gl_configs maps key " + key + " to account id " + config.getAccountId()
                                + " which no longer exists in company " + companyId + "."));

        if (!account.isActive()) {
            throw new IllegalStateException(
                    "gl_configs maps key " + key + " to inactive account "
                            + account.getAccountCode() + " in company " + companyId
                            + ". Activate the account or remap the config (BR-GL-10).");
        }
        return account;
    }
}
