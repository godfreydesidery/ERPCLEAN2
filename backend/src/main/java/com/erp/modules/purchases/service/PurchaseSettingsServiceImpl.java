package com.erp.modules.purchases.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.PurchaseSettingsDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseSettingsRequest;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PurchaseSettingsServiceImpl implements PurchaseSettingsService {

    private final PurchaseSettingsRepository settings;
    private final CompanyRepository          companies;
    private final ScopeGuard                 scopeGuard;

    public PurchaseSettingsServiceImpl(PurchaseSettingsRepository settings,
                                       CompanyRepository companies,
                                       ScopeGuard scopeGuard) {
        this.settings   = settings;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseSettingsDto getByCompanyUid(String companyUid) {
        Long companyId = resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        PurchaseSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> defaultSettings(companyId));
        return PurchaseSettingsDto.from(s);
    }

    @Override
    public PurchaseSettingsDto update(UpdatePurchaseSettingsRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        PurchaseSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> {
                    PurchaseSettings created = new PurchaseSettings(companyId, actorId());
                    return settings.save(created);
                });

        s.setPoApprovalEnabled(req.poApprovalEnabled());
        s.setPoApprovalThresholdAmount(req.poApprovalThresholdAmount());
        if (req.currency() != null && !req.currency().isBlank()) {
            s.setCurrency(CurrencyCode.ofNullable(req.currency()));
        }
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        return PurchaseSettingsDto.from(settings.save(s));
    }

    // -------------------------------------------------------------------------

    /** Returns an unsaved default; callers within a readOnly tx get a transient fallback. */
    private PurchaseSettings defaultSettings(Long companyId) {
        return new PurchaseSettings(companyId, null);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + companyUid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
