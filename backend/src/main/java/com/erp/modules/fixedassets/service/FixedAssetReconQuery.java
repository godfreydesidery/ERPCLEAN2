package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.FixedAssetReconciliationDto;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FA-to-GL reconciliation (ADR-0030 D-9, BR-FA-08).
 * Compares the register sums (Σ carrying_cost / Σ accumulated_depreciation WHERE IN_SERVICE)
 * to the GL account balances for FIXED_ASSETS and ACCUMULATED_DEPRECIATION.
 */
@Component
public class FixedAssetReconQuery {

    private final FixedAssetRepository    assets;
    private final GlConfigRepository      glConfigs;
    private final JournalLineRepository   journalLines;
    private final ScopeGuard              scopeGuard;

    public FixedAssetReconQuery(FixedAssetRepository assets,
                                 GlConfigRepository glConfigs,
                                 JournalLineRepository journalLines,
                                 ScopeGuard scopeGuard) {
        this.assets       = assets;
        this.glConfigs    = glConfigs;
        this.journalLines = journalLines;
        this.scopeGuard   = scopeGuard;
    }

    @Transactional(readOnly = true)
    public FixedAssetReconciliationDto reconcile(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        BigDecimal registerCost = assets.sumCarryingCostInService(companyId);
        BigDecimal registerAccum = assets.sumAccumulatedDepreciationInService(companyId);

        Long fixedAssetAccountId = resolveAccountId(companyId, GlConfigKey.FIXED_ASSETS);
        Long accumDepAccountId   = resolveAccountId(companyId, GlConfigKey.ACCUMULATED_DEPRECIATION);

        // GL balance for fixed assets (debit-normal: SUM(debit) - SUM(credit))
        BigDecimal glCostBalance = journalLines.accountBalance(companyId, fixedAssetAccountId);

        // Accumulated depreciation is credit-normal contra-asset; negate for positive presentation
        BigDecimal glAccumRaw  = journalLines.accountBalance(companyId, accumDepAccountId);
        BigDecimal glAccumBalance = glAccumRaw == null ? BigDecimal.ZERO : glAccumRaw.negate();

        boolean costTies     = registerCost.compareTo(glCostBalance == null ? BigDecimal.ZERO : glCostBalance) == 0;
        boolean accumDepTies = registerAccum.compareTo(glAccumBalance) == 0;

        return new FixedAssetReconciliationDto(
                registerCost,
                glCostBalance == null ? BigDecimal.ZERO : glCostBalance,
                costTies,
                registerAccum,
                glAccumBalance,
                accumDepTies);
    }

    private Long resolveAccountId(Long companyId, GlConfigKey key) {
        return glConfigs.findByCompanyIdAndConfigKey(companyId, key)
                .map(c -> c.getAccountId())
                .orElseThrow(() -> new NotFoundException(
                        "gl_configs mapping missing for key " + key + " in company " + companyId));
    }
}
