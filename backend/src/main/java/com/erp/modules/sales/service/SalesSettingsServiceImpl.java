package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SalesSettingsServiceImpl implements SalesSettingsService {

    private final SalesSettingsRepository settings;
    private final CompanyRepository       companies;
    private final ScopeGuard              scopeGuard;

    public SalesSettingsServiceImpl(SalesSettingsRepository settings,
                                    CompanyRepository companies,
                                    ScopeGuard scopeGuard) {
        this.settings   = settings;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesSettingsDto getByCompanyUid(String companyUid) {
        Long companyId = resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        SalesSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> defaultSettings(companyId));
        return SalesSettingsDto.from(s);
    }

    @Override
    public SalesSettingsDto update(UpdateSalesSettingsRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        SalesSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> {
                    SalesSettings created = new SalesSettings(companyId, actorId());
                    return settings.save(created);
                });

        s.setSoApprovalEnabled(req.soApprovalEnabled());
        s.setSoApprovalThresholdAmount(req.soApprovalThresholdAmount());
        if (req.currency() != null && !req.currency().isBlank()) {
            s.setCurrency(CurrencyCode.ofNullable(req.currency()));
        }
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        return SalesSettingsDto.from(settings.save(s));
    }

    // -------------------------------------------------------------------------

    /** Returns an unsaved default; callers within a readOnly tx get a transient fallback. */
    private SalesSettings defaultSettings(Long companyId) {
        return new SalesSettings(companyId, null);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
