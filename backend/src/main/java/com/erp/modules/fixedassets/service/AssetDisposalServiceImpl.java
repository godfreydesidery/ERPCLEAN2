package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetDisposalDto;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.WriteOffAssetRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.entity.AssetDisposal;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.AssetDisposalType;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.AssetDisposalRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.service.FiscalPeriodResolver;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetDisposalServiceImpl implements AssetDisposalService {

    private final FixedAssetRepository     assets;
    private final AssetCategoryRepository  categories;
    private final AssetDisposalRepository  disposals;
    private final FiscalPeriodRepository   fiscalPeriodRepo;
    private final FiscalPeriodResolver     periodResolver;
    private final FixedAssetGlPoster       glPoster;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public AssetDisposalServiceImpl(FixedAssetRepository assets,
                                     AssetCategoryRepository categories,
                                     AssetDisposalRepository disposals,
                                     FiscalPeriodRepository fiscalPeriodRepo,
                                     FiscalPeriodResolver periodResolver,
                                     FixedAssetGlPoster glPoster,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.assets          = assets;
        this.categories      = categories;
        this.disposals       = disposals;
        this.fiscalPeriodRepo = fiscalPeriodRepo;
        this.periodResolver  = periodResolver;
        this.glPoster        = glPoster;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public AssetDisposalDto dispose(String assetUid, DisposeAssetRequest req) {
        return doDispose(assetUid, req.disposalDate(), req.proceedsAmount(),
                AssetDisposalType.SALE, req.reason());
    }

    @Override
    public AssetDisposalDto writeOff(String assetUid, WriteOffAssetRequest req) {
        return doDispose(assetUid, req.disposalDate(), BigDecimal.ZERO,
                AssetDisposalType.WRITE_OFF, req.reason());
    }

    private AssetDisposalDto doDispose(String assetUid, java.time.LocalDate disposalDate,
                                        BigDecimal proceeds, AssetDisposalType disposalType,
                                        String reason) {
        FixedAsset asset = requireAsset(assetUid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());

        if (asset.getStatus() != FixedAssetStatus.IN_SERVICE) {
            throw new IllegalStateException("Only IN_SERVICE assets can be disposed (BR-FA-03).");
        }
        if (disposals.findByFixedAssetId(asset.getId()).isPresent()) {
            throw new IllegalStateException("Asset has already been disposed.");
        }

        AssetCategory category = requireCategory(asset);

        // Period gate
        FiscalPeriod period = periodResolver.resolveOpen(asset.getCompanyId(), disposalDate);

        String currency = "TZS";
        BigDecimal nbv     = asset.computeNbv();
        BigDecimal gainLoss = proceeds.subtract(nbv);

        // Post disposal GL journal (D-6c / D-6d)
        // We use a temporary uid — will fill after disposal is saved
        // Build a placeholder uid to pass as sourceRef; real uid will be set after save
        AssetDisposal disposal = new AssetDisposal(
                asset.getCompanyId(), asset.getBranchId(), asset.getId(),
                disposalType, disposalDate, period.getId(),
                proceeds, nbv, gainLoss, currency, reason, actorId());
        disposal = disposals.save(disposal);

        JournalEntryDto journal = glPoster.postDisposal(
                asset, category, disposalType, proceeds, disposalDate,
                disposal.getUid(), currency, actorId());

        disposal.setGlEntryUid(journal.uid());
        disposal.setUpdatedAt(Instant.now());
        disposal.setUpdatedBy(actorId());

        // Update asset status
        FixedAssetStatus newStatus = disposalType == AssetDisposalType.WRITE_OFF
                ? FixedAssetStatus.WRITTEN_OFF : FixedAssetStatus.DISPOSED;
        asset.setStatus(newStatus);
        asset.setDisposedAt(disposalDate);
        asset.setUpdatedAt(Instant.now());
        asset.setUpdatedBy(actorId());
        assets.save(asset);

        String action = disposalType == AssetDisposalType.WRITE_OFF
                ? AuditActions.FA_ASSET_WRITE_OFF : AuditActions.FA_ASSET_DISPOSE;
        audit.record(AuditEvent.of(action, "fixed_assets", asset.getId(), asset.getUid())
                .detail(Map.of("assetNumber", asset.getAssetNumber(),
                        "disposalType", disposalType.name(),
                        "gainLoss", gainLoss.toPlainString())));

        return toDto(disposal);
    }

    private FixedAsset requireAsset(String uid) {
        return assets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FixedAsset", uid));
    }

    private AssetCategory requireCategory(FixedAsset asset) {
        return categories.findById(asset.getCategoryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Category not found: " + asset.getCategoryId()));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private AssetDisposalDto toDto(AssetDisposal d) {
        return new AssetDisposalDto(
                d.getId(), d.getUid(), d.getCompanyId(), d.getBranchId(),
                d.getFixedAssetId(), d.getDisposalType(), d.getDisposalDate(),
                d.getFiscalPeriodId(), d.getProceedsAmount(), d.getNbvAtDisposal(),
                d.getGainLossAmount(), d.getGlEntryUid(), d.getCurrency(), d.getReason());
    }
}
