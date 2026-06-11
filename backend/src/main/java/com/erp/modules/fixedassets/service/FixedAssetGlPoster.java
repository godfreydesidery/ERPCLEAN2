package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.AssetDisposalType;
import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds and posts ALL Fixed Assets GL journals via {@link GLPostingService} (ADR-0030 D-6).
 *
 * <p>All postings are synchronous human acts — a missing gl_config or closed period MUST fail
 * the operator's command (ADR-0030 D-3 "synchronous human-act posts directly" stance).
 * FA has no event-driven posting leg, so the safe-invoker REQUIRES_NEW is not used here.
 */
@Component
public class FixedAssetGlPoster {

    private final GLPostingService  posting;
    private final GLConfigResolver  resolver;

    public FixedAssetGlPoster(GLPostingService posting, GLConfigResolver resolver) {
        this.posting  = posting;
        this.resolver = resolver;
    }

    // -------------------------------------------------------------------------
    // (a) Capitalisation journal: DR Fixed Asset / CR Fixed Asset Clearing (D-6a, D-7)
    // -------------------------------------------------------------------------

    /**
     * Post the capitalisation journal when an asset is placed in service.
     * DR Fixed Asset (category assetAccountId) / CR Fixed Asset Clearing (1650).
     */
    public JournalEntryDto postCapitalisation(FixedAsset asset, AssetCategory category,
                                               LocalDate postingDate, String currency,
                                               Long postedBy) {
        Long clearingAccountId = resolver.resolve(asset.getCompanyId(), GlConfigKey.FIXED_ASSET_CLEARING)
                .getId();

        List<LineDraft> lines = List.of(
                new LineDraft(category.getAssetAccountId(),
                        asset.getAcquisitionCost(), BigDecimal.ZERO,
                        currency, "Capitalise " + asset.getAssetNumber() + " — " + asset.getName()),
                new LineDraft(clearingAccountId,
                        BigDecimal.ZERO, asset.getAcquisitionCost(),
                        currency, "FA clearing — " + asset.getAssetNumber())
        );

        JournalEntryDraft draft = new JournalEntryDraft(
                asset.getCompanyId(), asset.getBranchId(), postingDate,
                "Asset capitalisation — " + asset.getAssetNumber() + " " + asset.getName(),
                JournalSourceType.FA_ACQUISITION, asset.getUid(),
                null, postedBy, lines);

        return posting.post(draft);
    }

    // -------------------------------------------------------------------------
    // (b) Depreciation run: per-category DR Dep Expense / CR Accum Dep (D-6b)
    // Per-category leg pairs in one journal — categoryId → summedCharge map.
    // -------------------------------------------------------------------------

    /**
     * Post the depreciation run journal.
     * One journal per run; per-category leg pairs (DR dep expense / CR accum dep).
     *
     * @param categoryCharges map of (categoryId → (category, summedCharge))
     */
    public JournalEntryDto postDepreciationRun(
            Long companyId, Long branchId, LocalDate postingDate,
            String runNumber, String runUid, String currency, Long postedBy,
            Map<Long, CategoryCharge> categoryCharges) {

        List<LineDraft> lines = new ArrayList<>();
        for (Map.Entry<Long, CategoryCharge> entry : categoryCharges.entrySet()) {
            CategoryCharge cc = entry.getValue();
            if (cc.charge().compareTo(BigDecimal.ZERO) == 0) continue;
            lines.add(new LineDraft(cc.category().getDepExpenseAccountId(),
                    cc.charge(), BigDecimal.ZERO,
                    currency, "Dep expense — " + cc.category().getCode() + " — " + runNumber));
            lines.add(new LineDraft(cc.category().getAccumDepAccountId(),
                    BigDecimal.ZERO, cc.charge(),
                    currency, "Accum dep — " + cc.category().getCode() + " — " + runNumber));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate,
                "Depreciation run " + runNumber,
                JournalSourceType.DEPRECIATION, runUid,
                null, postedBy, lines);

        return posting.post(draft);
    }

    // -------------------------------------------------------------------------
    // (c) Disposal (SALE): D-6c
    // -------------------------------------------------------------------------

    /**
     * Post the disposal (SALE) journal.
     * CR Fixed Asset = acquisitionCost (gross removal)
     * DR Accum Dep = accumulated_depreciation
     * DR Fixed Asset Clearing = proceeds
     * +/- Gain/Loss to GAIN_LOSS_ON_DISPOSAL (CR gain, DR loss)
     */
    public JournalEntryDto postDisposal(FixedAsset asset, AssetCategory category,
                                         AssetDisposalType disposalType,
                                         BigDecimal proceedsAmount,
                                         LocalDate disposalDate, String disposalUid,
                                         String currency, Long postedBy) {
        BigDecimal nbv = asset.computeNbv();
        BigDecimal gainLoss = proceedsAmount.subtract(nbv); // positive = gain, negative = loss

        Long clearingAccountId = resolver.resolve(asset.getCompanyId(), GlConfigKey.FIXED_ASSET_CLEARING)
                .getId();
        Long gainLossAccountId = resolver.resolve(asset.getCompanyId(), GlConfigKey.GAIN_LOSS_ON_DISPOSAL)
                .getId();

        List<LineDraft> lines = new ArrayList<>();

        // DR Accumulated Depreciation (reverse the contra)
        if (asset.getAccumulatedDepreciation().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(category.getAccumDepAccountId(),
                    asset.getAccumulatedDepreciation(), BigDecimal.ZERO,
                    currency, "Remove accum dep — " + asset.getAssetNumber()));
        }

        // DR Clearing for proceeds (if any)
        if (proceedsAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(clearingAccountId,
                    proceedsAmount, BigDecimal.ZERO,
                    currency, "Disposal proceeds — " + asset.getAssetNumber()));
        }

        // Loss is a DR to gain/loss (gainLoss < 0 means a loss)
        if (gainLoss.compareTo(BigDecimal.ZERO) < 0) {
            lines.add(new LineDraft(gainLossAccountId,
                    gainLoss.negate(), BigDecimal.ZERO,
                    currency, "Loss on disposal — " + asset.getAssetNumber()));
        }

        // CR Fixed Asset (gross removal)
        lines.add(new LineDraft(category.getAssetAccountId(),
                BigDecimal.ZERO, asset.getAcquisitionCost(),
                currency, "Remove asset cost — " + asset.getAssetNumber()));

        // Gain is a CR to gain/loss
        if (gainLoss.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(gainLossAccountId,
                    BigDecimal.ZERO, gainLoss,
                    currency, "Gain on disposal — " + asset.getAssetNumber()));
        }

        JournalSourceType sourceType = disposalType == AssetDisposalType.WRITE_OFF
                ? JournalSourceType.FA_DISPOSAL
                : JournalSourceType.FA_DISPOSAL;

        JournalEntryDraft draft = new JournalEntryDraft(
                asset.getCompanyId(), asset.getBranchId(), disposalDate,
                "Asset disposal — " + asset.getAssetNumber() + " " + asset.getName(),
                sourceType, disposalUid,
                null, postedBy, lines);

        return posting.post(draft);
    }

    // -------------------------------------------------------------------------
    // (e) Revaluation: D-6e
    // -------------------------------------------------------------------------

    /**
     * Post the revaluation journal.
     * UP:   DR Fixed Asset / CR Revaluation Reserve
     * DOWN: DR Gain/Loss (loss) / CR Fixed Asset
     */
    public JournalEntryDto postRevaluation(FixedAsset asset, AssetCategory category,
                                            RevaluationDirection direction, BigDecimal delta,
                                            LocalDate revaluationDate, String revaluationUid,
                                            String currency, Long postedBy) {
        Long reserveAccountId  = resolver.resolve(asset.getCompanyId(), GlConfigKey.REVALUATION_RESERVE)
                .getId();
        Long gainLossAccountId = resolver.resolve(asset.getCompanyId(), GlConfigKey.GAIN_LOSS_ON_DISPOSAL)
                .getId();

        List<LineDraft> lines;
        if (direction == RevaluationDirection.UP) {
            lines = List.of(
                    new LineDraft(category.getAssetAccountId(),
                            delta, BigDecimal.ZERO,
                            currency, "Revalue up — " + asset.getAssetNumber()),
                    new LineDraft(reserveAccountId,
                            BigDecimal.ZERO, delta,
                            currency, "Revaluation reserve — " + asset.getAssetNumber())
            );
        } else {
            lines = List.of(
                    new LineDraft(gainLossAccountId,
                            delta, BigDecimal.ZERO,
                            currency, "Revalue down (loss) — " + asset.getAssetNumber()),
                    new LineDraft(category.getAssetAccountId(),
                            BigDecimal.ZERO, delta,
                            currency, "Revalue down asset — " + asset.getAssetNumber())
            );
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                asset.getCompanyId(), asset.getBranchId(), revaluationDate,
                "Asset revaluation — " + asset.getAssetNumber() + " " + direction.name(),
                JournalSourceType.FA_REVALUATION, revaluationUid,
                null, postedBy, lines);

        return posting.post(draft);
    }

    // -------------------------------------------------------------------------
    // Value objects
    // -------------------------------------------------------------------------

    /** Per-category summed charge for the run journal. */
    public record CategoryCharge(AssetCategory category, BigDecimal charge) {}
}
