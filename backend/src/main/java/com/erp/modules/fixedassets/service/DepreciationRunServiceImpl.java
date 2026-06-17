package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.DepreciationRunDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunExecutedPayload;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunLineDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunPreviewDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunPreviewLineDto;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.entity.DepreciationRun;
import com.erp.modules.fixedassets.domain.entity.DepreciationRunLine;
import com.erp.modules.fixedassets.domain.entity.DepreciationScheduleLine;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.DepreciationRunLineRepository;
import com.erp.modules.fixedassets.repository.DepreciationRunRepository;
import com.erp.modules.fixedassets.repository.DepreciationScheduleLineRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.fixedassets.service.FixedAssetGlPoster.CategoryCharge;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.service.FiscalPeriodResolver;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DepreciationRunServiceImpl implements DepreciationRunService {

    private final DepreciationRunRepository         runs;
    private final DepreciationRunLineRepository     runLines;
    private final DepreciationScheduleLineRepository schedules;
    private final FixedAssetRepository              assets;
    private final AssetCategoryRepository           categories;
    private final FiscalPeriodRepository            fiscalPeriods;
    private final FiscalPeriodResolver              periodResolver;
    private final FixedAssetGlPoster                glPoster;
    private final FixedAssetNumberGenerator         numberGenerator;
    private final OutboxPublisher                   outbox;
    private final ScopeGuard                        scopeGuard;
    private final AuditService                      audit;

    public DepreciationRunServiceImpl(
            DepreciationRunRepository runs,
            DepreciationRunLineRepository runLines,
            DepreciationScheduleLineRepository schedules,
            FixedAssetRepository assets,
            AssetCategoryRepository categories,
            FiscalPeriodRepository fiscalPeriods,
            FiscalPeriodResolver periodResolver,
            FixedAssetGlPoster glPoster,
            FixedAssetNumberGenerator numberGenerator,
            OutboxPublisher outbox,
            ScopeGuard scopeGuard,
            AuditService audit) {
        this.runs            = runs;
        this.runLines        = runLines;
        this.schedules       = schedules;
        this.assets          = assets;
        this.categories      = categories;
        this.fiscalPeriods   = fiscalPeriods;
        this.periodResolver  = periodResolver;
        this.glPoster        = glPoster;
        this.numberGenerator = numberGenerator;
        this.outbox          = outbox;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public DepreciationRunPreviewDto preview(Long companyId, String fiscalPeriodUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        FiscalPeriod period = requirePeriod(fiscalPeriodUid);

        List<DepreciationScheduleLine> eligibleLines =
                schedules.findEligibleForRun(companyId, period.getStartDate());

        List<DepreciationRunPreviewLineDto> previewLines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (DepreciationScheduleLine sl : eligibleLines) {
            FixedAsset asset = assets.findById(sl.getFixedAssetId()).orElse(null);
            if (asset == null) continue;
            previewLines.add(new DepreciationRunPreviewLineDto(
                    asset.getId(), asset.getUid(), asset.getAssetNumber(), asset.getName(),
                    asset.getCategoryId(), sl.getPeriodSeq(), sl.getPlannedCharge()));
            total = total.add(sl.getPlannedCharge());
        }

        return new DepreciationRunPreviewDto(companyId, fiscalPeriodUid,
                previewLines.size(), total, previewLines);
    }

    @Override
    public DepreciationRunDto post(RunDepreciationRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());

        FiscalPeriod period = requirePeriod(req.fiscalPeriodUid());

        // Idempotency guard (D-4): depreciation run is once per (company, period) — reject repeats
        Optional<DepreciationRun> existing =
                runs.findByCompanyIdAndFiscalPeriodId(req.companyId(), period.getId());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                    "Depreciation run already posted for company=" + req.companyId()
                            + " period=" + req.fiscalPeriodUid()
                            + " (run=" + existing.get().getRunNumber() + "). "
                            + "Duplicate runs are not allowed (ADR-0030 D-4).");
        }

        // Period gate — must be OPEN and postingDate must fall in it
        periodResolver.resolveOpen(req.companyId(), req.postingDate());

        // Find eligible schedule lines
        List<DepreciationScheduleLine> eligibleLines =
                schedules.findEligibleForRun(req.companyId(), period.getStartDate());

        if (eligibleLines.isEmpty()) {
            throw new IllegalStateException(
                    "No eligible assets found for depreciation run in period " + req.fiscalPeriodUid());
        }

        // Build per-category charge map (ADR-0030 D-4, step 5)
        Map<Long, CategoryCharge> categoryCharges = new LinkedHashMap<>();
        Map<Long, FixedAsset>     assetMap        = new LinkedHashMap<>();

        for (DepreciationScheduleLine sl : eligibleLines) {
            FixedAsset asset = assets.findById(sl.getFixedAssetId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Asset not found: " + sl.getFixedAssetId()));
            assetMap.put(asset.getId(), asset);

            Long catId = asset.getCategoryId();
            AssetCategory cat = categories.findById(catId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Category not found: " + catId));

            categoryCharges.merge(catId,
                    new CategoryCharge(cat, sl.getPlannedCharge()),
                    (existing2, next) -> new CategoryCharge(cat,
                            existing2.charge().add(next.charge())));
        }

        // Allocate run number
        String runNumber = numberGenerator.nextRunNumber(req.companyId());

        // Derive currency — use company base currency (simplified: "TZS")
        String currency = "TZS";

        // Post one GL journal (D-4 step 6)
        JournalEntryDto journal = glPoster.postDepreciationRun(
                req.companyId(),
                eligibleLines.get(0) != null
                        ? assetMap.get(eligibleLines.get(0).getFixedAssetId()).getBranchId() : null,
                req.postingDate(), runNumber, null /* will fill after save */,
                currency, actorId(), categoryCharges);

        // Persist the run header
        BigDecimal totalCharge = categoryCharges.values().stream()
                .map(CategoryCharge::charge)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DepreciationRun run = new DepreciationRun(
                req.companyId(), runNumber, period.getId(),
                req.postingDate(), currency, actorId());
        run.setTotalChargeAmount(totalCharge);
        run.setAssetCount(eligibleLines.size());
        run.setGlEntryUid(journal.uid());
        run = runs.save(run);

        // Persist run lines + update schedule lines + update asset accum dep
        List<DepreciationRunLine> savedLines = new ArrayList<>();
        for (DepreciationScheduleLine sl : eligibleLines) {
            FixedAsset asset = assetMap.get(sl.getFixedAssetId());

            BigDecimal newAccum = asset.getAccumulatedDepreciation().add(sl.getPlannedCharge());
            BigDecimal newNbv   = asset.getCarryingCost().subtract(newAccum);

            // Mark schedule line as posted
            sl.setPosted(true);
            sl.setDepreciationRunId(run.getId());
            sl.setUpdatedAt(Instant.now());
            sl.setUpdatedBy(actorId());
            schedules.save(sl);

            // Update asset accumulated depreciation
            asset.setAccumulatedDepreciation(newAccum);
            asset.setUpdatedAt(Instant.now());
            asset.setUpdatedBy(actorId());
            assets.save(asset);

            // Create run line
            DepreciationRunLine line = new DepreciationRunLine(
                    run.getId(), req.companyId(), asset.getId(), sl.getId(),
                    sl.getPlannedCharge(), newAccum, newNbv, actorId());
            savedLines.add(runLines.save(line));
        }

        // Emit DEPRECIATION.RUN.EXECUTED event (D-8)
        outbox.publish(
                DomainEventType.DEPRECIATION_RUN_EXECUTED,
                DomainEventType.AGG_DEPRECIATION_RUN,
                run.getId(), run.getUid(),
                req.companyId(), null,
                new DepreciationRunExecutedPayload(
                        run.getUid(), req.companyId(), period.getId(),
                        req.postingDate(), totalCharge, eligibleLines.size(),
                        journal.uid(), run.getExecutedAt()));

        audit.record(AuditEvent.of(AuditActions.FA_DEPRECIATION_RUN, "depreciation_runs",
                        run.getId(), run.getUid())
                .detail(Map.of("runNumber", runNumber,
                        "period", req.fiscalPeriodUid(),
                        "assetCount", String.valueOf(eligibleLines.size()),
                        "totalCharge", totalCharge.toPlainString())));

        return toDto(run, savedLines);
    }

    @Override
    @Transactional(readOnly = true)
    public DepreciationRunDto getByUid(String uid) {
        DepreciationRun run = runs.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DepreciationRun", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());
        return toDto(run, runLines.findByDepreciationRunId(run.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepreciationRunDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return runs.findByCompanyId(companyId, pageable)
                .map(r -> toDto(r, runLines.findByDepreciationRunId(r.getId())));
    }

    // -------------------------------------------------------------------------

    private FiscalPeriod requirePeriod(String uid) {
        return fiscalPeriods.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FiscalPeriod", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private DepreciationRunDto toDto(DepreciationRun r, List<DepreciationRunLine> lines) {
        return new DepreciationRunDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getRunNumber(),
                r.getFiscalPeriodId(), r.getPostingDate(), r.getStatus(),
                r.getTotalChargeAmount(), r.getAssetCount(), r.getGlEntryUid(),
                CurrencyCode.value(r.getCurrency()), r.getExecutedAt(),
                lines.stream().map(l -> new DepreciationRunLineDto(
                        l.getId(), l.getUid(), l.getFixedAssetId(), l.getScheduleLineId(),
                        l.getChargeAmount(), l.getAccumDepAfter(), l.getNbvAfter()))
                        .toList());
    }
}
