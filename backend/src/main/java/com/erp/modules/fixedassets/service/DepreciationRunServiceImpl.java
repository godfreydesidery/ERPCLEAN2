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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate                      jdbc;

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
            AuditService audit,
            JdbcTemplate jdbc) {
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
        this.jdbc            = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public DepreciationRunPreviewDto preview(Long companyId, String fiscalPeriodUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        FiscalPeriod period = requirePeriod(companyId, fiscalPeriodUid);

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

        FiscalPeriod period = requirePeriod(req.companyId(), req.fiscalPeriodUid());

        // Idempotency guard (D-4): depreciation run is once per (company, period) — reject repeats
        Optional<DepreciationRun> existing =
                runs.findByCompanyIdAndFiscalPeriodId(req.companyId(), period.getId());
        if (existing.isPresent()) {
            // ADR-0030 D-4: only one depreciation run is allowed per company per fiscal period.
            throw new IllegalStateException(
                    "A depreciation run has already been posted for the selected fiscal period. "
                    + "Only one run per period is allowed.");
        }

        // Period gate — must be OPEN and postingDate must fall in it
        periodResolver.resolveOpen(req.companyId(), req.postingDate());

        // Find eligible schedule lines
        List<DepreciationScheduleLine> eligibleLines =
                schedules.findEligibleForRun(req.companyId(), period.getStartDate());

        if (eligibleLines.isEmpty()) {
            // req.fiscalPeriodUid() intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "No eligible assets were found for a depreciation run in the selected period.");
        }

        // Build the per-BRANCH, per-category charge map (ADR-0030 D-4 step 5). The GL engine stamps
        // one branch on a journal and all of its lines, so a run that spans branches must post one
        // journal per asset branch - otherwise every branch's depreciation lands in whichever branch
        // the first eligible asset happened to belong to (a BR-02 van booked to BR-01's P&L).
        // TreeMap with nulls first: deterministic journal order, and a branch-less asset (legacy
        // data) gets a company-level journal instead of borrowing another asset's branch.
        Map<Long, Map<Long, CategoryCharge>> chargesByBranch =
                new TreeMap<>(Comparator.nullsFirst(Comparator.<Long>naturalOrder()));
        Map<Long, FixedAsset>     assetMap        = new LinkedHashMap<>();

        for (DepreciationScheduleLine sl : eligibleLines) {
            FixedAsset asset = assets.findById(sl.getFixedAssetId())
                    .orElseThrow(() -> new IllegalStateException(
                            "A fixed asset referenced by the depreciation schedule could not be found. Please contact support."));
            assetMap.put(asset.getId(), asset);

            Long catId = asset.getCategoryId();
            AssetCategory cat = categories.findById(catId)
                    .orElseThrow(() -> new IllegalStateException(
                            "An asset category referenced by the depreciation schedule could not be found. Please contact support."));

            chargesByBranch
                    .computeIfAbsent(asset.getBranchId(), b -> new LinkedHashMap<>())
                    .merge(catId,
                            new CategoryCharge(cat, sl.getPlannedCharge()),
                            (existing2, next) -> new CategoryCharge(cat,
                                    existing2.charge().add(next.charge())));
        }

        // Allocate run number
        String runNumber = numberGenerator.nextRunNumber(req.companyId());

        // Derive currency - use company base currency (simplified: "TZS")
        String currency = "TZS";

        DepreciationRun run = new DepreciationRun(
                req.companyId(), runNumber, period.getId(),
                req.postingDate(), currency, actorId());
        String runUid = run.reserveUid();

        // Post one GL journal per asset branch (D-4 step 6, per branch), each carrying the run uid
        // as its source_ref so the run's journals can be found together.
        List<String> journalUids = new ArrayList<>();
        BigDecimal totalCharge = BigDecimal.ZERO;
        for (Map.Entry<Long, Map<Long, CategoryCharge>> branchCharges : chargesByBranch.entrySet()) {
            BigDecimal branchTotal = branchCharges.getValue().values().stream()
                    .map(CategoryCharge::charge)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            totalCharge = totalCharge.add(branchTotal);
            if (branchTotal.signum() == 0) {
                continue; // nothing to book for this branch this period
            }
            JournalEntryDto journal = glPoster.postDepreciationRun(
                    req.companyId(), branchCharges.getKey(),
                    req.postingDate(), runNumber, runUid,
                    currency, actorId(), branchCharges.getValue());
            journalUids.add(journal.uid());
        }
        if (journalUids.isEmpty()) {
            throw new IllegalStateException(
                    "No eligible assets were found for a depreciation run in the selected period.");
        }

        // Persist the run header
        run.setTotalChargeAmount(totalCharge);
        run.setAssetCount(eligibleLines.size());
        run.setGlEntryUid(journalUids.get(0));
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
                        run.getGlEntryUid(), run.getExecutedAt()));

        audit.record(AuditEvent.of(AuditActions.FA_DEPRECIATION_RUN, "depreciation_runs",
                        run.getId(), run.getUid())
                .detail(Map.of("runNumber", runNumber,
                        "period", req.fiscalPeriodUid(),
                        "assetCount", String.valueOf(eligibleLines.size()),
                        "journalCount", String.valueOf(journalUids.size()),
                        "totalCharge", totalCharge.toPlainString())));

        return toDto(run, savedLines, journalUids);
    }

    @Override
    @Transactional(readOnly = true)
    public DepreciationRunDto getByUid(String uid) {
        DepreciationRun run = runs.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DepreciationRun", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());
        return toDto(run, runLines.findByDepreciationRunId(run.getId()), journalUidsOf(run));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepreciationRunDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return runs.findByCompanyId(companyId, pageable)
                .map(r -> toDto(r, runLines.findByDepreciationRunId(r.getId()), journalUidsOf(r)));
    }

    // -------------------------------------------------------------------------

    /**
     * Company-scoped period lookup. Returns 404 when the period does not exist OR belongs to a
     * different company, preventing a caller from referencing a foreign tenant's fiscal period
     * (tenant-isolation site 4 — confused-deputy on preview/post).
     */
    private FiscalPeriod requirePeriod(Long companyId, String uid) {
        return fiscalPeriods.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> NotFoundException.of("FiscalPeriod", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    /**
     * The run's journals, read back by the run uid every journal carries as its source_ref. A run
     * posted before the per-branch split has a null source_ref on its single journal - it falls back
     * to the header's gl_entry_uid. Scalar SQL: FA reads GL ids, it does not load GL entities.
     */
    private List<String> journalUidsOf(DepreciationRun r) {
        List<String> uids = jdbc.queryForList(
                """
                SELECT uid FROM journal_entries
                WHERE company_id = ? AND source_type = 'DEPRECIATION' AND source_ref = ?
                ORDER BY id
                """,
                String.class, r.getCompanyId(), r.getUid());
        if (uids.isEmpty() && r.getGlEntryUid() != null) {
            return List.of(r.getGlEntryUid());
        }
        return uids;
    }

    private DepreciationRunDto toDto(DepreciationRun r, List<DepreciationRunLine> lines,
                                     List<String> journalUids) {
        return new DepreciationRunDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getRunNumber(),
                r.getFiscalPeriodId(), r.getPostingDate(), r.getStatus(),
                r.getTotalChargeAmount(), r.getAssetCount(), r.getGlEntryUid(), journalUids,
                CurrencyCode.value(r.getCurrency()), r.getExecutedAt(),
                lines.stream().map(l -> new DepreciationRunLineDto(
                        l.getId(), l.getUid(), l.getFixedAssetId(), l.getScheduleLineId(),
                        l.getChargeAmount(), l.getAccumDepAfter(), l.getNbvAfter()))
                        .toList());
    }
}
