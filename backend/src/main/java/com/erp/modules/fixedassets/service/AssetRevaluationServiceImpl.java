package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetRevaluationDto;
import com.erp.modules.fixedassets.domain.dto.RevalueAssetRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.entity.AssetRevaluation;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.AssetRevaluationRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.service.FiscalPeriodResolver;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetRevaluationServiceImpl implements AssetRevaluationService {

    private final FixedAssetRepository        assets;
    private final AssetCategoryRepository     categories;
    private final AssetRevaluationRepository  revaluations;
    private final FiscalPeriodResolver        periodResolver;
    private final FixedAssetGlPoster          glPoster;
    private final DepreciationScheduleService scheduleService;
    private final ScopeGuard                  scopeGuard;
    private final AuditService                audit;

    public AssetRevaluationServiceImpl(FixedAssetRepository assets,
                                        AssetCategoryRepository categories,
                                        AssetRevaluationRepository revaluations,
                                        FiscalPeriodResolver periodResolver,
                                        FixedAssetGlPoster glPoster,
                                        DepreciationScheduleService scheduleService,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.assets          = assets;
        this.categories      = categories;
        this.revaluations    = revaluations;
        this.periodResolver  = periodResolver;
        this.glPoster        = glPoster;
        this.scheduleService = scheduleService;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public AssetRevaluationDto revalue(String assetUid, RevalueAssetRequest req) {
        FixedAsset asset = requireAsset(assetUid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());

        if (asset.getStatus() != FixedAssetStatus.IN_SERVICE) {
            throw new IllegalStateException("Only IN_SERVICE assets can be revalued.");
        }

        AssetCategory category = requireCategory(asset);

        // Period gate
        FiscalPeriod period = periodResolver.resolveOpen(asset.getCompanyId(), req.revaluationDate());

        BigDecimal carryingBefore = asset.getCarryingCost();
        BigDecimal delta = req.deltaAmount();
        BigDecimal carryingAfter;

        if (req.direction() == RevaluationDirection.UP) {
            carryingAfter = carryingBefore.add(delta);
            asset.setRevaluationReserveBalance(
                    asset.getRevaluationReserveBalance().add(delta));
        } else {
            carryingAfter = carryingBefore.subtract(delta);
            if (carryingAfter.compareTo(asset.getAccumulatedDepreciation()) < 0) {
                throw new IllegalArgumentException(
                        "Down-revaluation would reduce carrying cost below accumulated depreciation.");
            }
        }

        String currency = "TZS";

        // Save revaluation record first to get the uid for sourceRef
        AssetRevaluation reval = new AssetRevaluation(
                asset.getCompanyId(), asset.getBranchId(), asset.getId(),
                req.revaluationDate(), period.getId(), req.direction(), delta,
                carryingBefore, carryingAfter, currency, req.reason(), actorId());
        reval = revaluations.save(reval);

        // Post the revaluation GL journal (D-6e)
        JournalEntryDto journal = glPoster.postRevaluation(
                asset, category, req.direction(), delta,
                req.revaluationDate(), reval.getUid(), currency, actorId());

        reval.setGlEntryUid(journal.uid());
        reval.setUpdatedAt(Instant.now());
        reval.setUpdatedBy(actorId());

        // Update asset carrying cost
        asset.setCarryingCost(carryingAfter);
        asset.setUpdatedAt(Instant.now());
        asset.setUpdatedBy(actorId());
        assets.save(asset);

        // Regenerate the remaining depreciation schedule (D-5)
        scheduleService.regenerate(asset, actorId());

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_REVALUE, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("direction", req.direction().name(),
                        "delta", delta.toPlainString(),
                        "carryingAfter", carryingAfter.toPlainString())));

        return toDto(reval);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetRevaluationDto> listByAsset(String assetUid) {
        FixedAsset asset = requireAsset(assetUid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());
        return revaluations.findByFixedAssetIdOrderByRevaluationDateAsc(asset.getId())
                .stream().map(this::toDto).toList();
    }

    private FixedAsset requireAsset(String uid) {
        return assets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FixedAsset", uid));
    }

    private AssetCategory requireCategory(FixedAsset asset) {
        return categories.findById(asset.getCategoryId())
                .orElseThrow(() -> new IllegalStateException(
                        "The asset's category could not be found."));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private AssetRevaluationDto toDto(AssetRevaluation r) {
        return new AssetRevaluationDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(),
                r.getFixedAssetId(), r.getRevaluationDate(), r.getFiscalPeriodId(),
                r.getDirection(), r.getDeltaAmount(), r.getCarryingBefore(),
                r.getCarryingAfter(), r.getGlEntryUid(), CurrencyCode.value(r.getCurrency()), r.getReason(),
                r.getValuerName(), r.getValuationRef(), r.getApprovedBy());
    }
}
