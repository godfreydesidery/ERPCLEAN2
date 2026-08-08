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
        rejectCeilingWithoutStance(req);

        SalesSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> {
                    SalesSettings created = new SalesSettings(companyId, actorId());
                    return settings.save(created);
                });

        s.setSoApprovalEnabled(req.soApprovalEnabled());
        s.setSoApprovalThresholdAmount(req.soApprovalThresholdAmount());
        s.setAllowNegativeStock(req.allowNegativeStock());
        // V93: an absent below-cost policy means OFF — the same answer getByCompanyUid gives for a
        // company with no row at all, and the same one BelowCostGuard enforces. Keeping the three
        // in lockstep is the whole point (see NegativeStockSettingCrossLayerContractTest).
        // Absent means "leave as it is", NOT "switch it off". Mapping null to OFF would let any
        // caller that omits the field silently disable a configured BLOCK/APPROVE policy — a
        // safety control that turns itself off without anyone choosing to. A new row still starts
        // at OFF, because that is the entity's own default.
        if (req.belowCostAction() != null) {
            s.setBelowCostAction(req.belowCostAction());
        }
        // V95 (K7): the discount stance and its ceiling are ONE policy, applied together or not at
        // all. Absent stance = "leave the policy exactly as it is", for the same reason as
        // below-cost: a caller that omits the field must not be able to switch a configured control
        // off by accident. Applying the pair together is what makes the ceiling clearable — a
        // present stance with a null ceiling is a real instruction ("no ceiling configured", which
        // the guard reads as ZERO), and treating null as "leave alone" would make that unsayable.
        // A half-written pair never reaches here — rejectCeilingWithoutStance refuses it up front.
        if (req.discountApprovalAction() != null) {
            s.setDiscountApprovalAction(req.discountApprovalAction());
            s.setMaxDiscountPercent(req.maxDiscountPercent());
        }
        if (req.currency() != null && !req.currency().isBlank()) {
            s.setCurrency(CurrencyCode.ofNullable(req.currency()));
        }
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        return SalesSettingsDto.from(settings.save(s));
    }

    // -------------------------------------------------------------------------

    /**
     * Refuses a discount ceiling that arrives without the stance it belongs to (UAT finding #3).
     *
     * <p>The stance and the ceiling are ONE policy and are applied together — that part is
     * deliberate, and it is what stops a client written before K7 from silently switching a
     * configured control off by omitting a field it has never heard of. The defect was the ANSWER:
     * a request carrying only {@code maxDiscountPercent} was accepted with 200 OK, saved nothing,
     * and handed back settings showing the old ceiling. An administrator reads that as "saved", and
     * the shop then runs on a limit nobody set.
     *
     * <p>Refusing it is safe for every existing caller: "leave the policy alone" is expressed by
     * sending NEITHER field, which is exactly what a pre-K7 client does. Only a caller that
     * genuinely tried to set half the policy is turned away, and it is told precisely how to fix it.
     */
    private static void rejectCeilingWithoutStance(UpdateSalesSettingsRequest req) {
        if (req.discountApprovalAction() == null && req.maxDiscountPercent() != null) {
            throw new IllegalArgumentException(
                    "maxDiscountPercent: a maximum discount percent only takes effect together with "
                    + "the discount approval action. Send both, or neither.");
        }
    }

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
