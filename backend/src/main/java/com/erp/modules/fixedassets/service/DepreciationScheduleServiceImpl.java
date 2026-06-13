package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.DepreciationScheduleLineDto;
import com.erp.modules.fixedassets.domain.entity.DepreciationScheduleLine;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.repository.DepreciationScheduleLineRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.platform.common.api.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DepreciationScheduleServiceImpl implements DepreciationScheduleService {

    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final DepreciationScheduleLineRepository schedules;
    private final FixedAssetRepository assets;

    public DepreciationScheduleServiceImpl(DepreciationScheduleLineRepository schedules,
                                            FixedAssetRepository assets) {
        this.schedules = schedules;
        this.assets    = assets;
    }

    @Override
    public void generate(FixedAsset asset, Long createdBy) {
        generateSchedule(asset, 1, BigDecimal.ZERO, createdBy);
    }

    @Override
    public void regenerate(FixedAsset asset, Long createdBy) {
        // Find the current max schedule version
        List<DepreciationScheduleLine> current =
                schedules.findCurrentVersionByAssetId(asset.getId());

        // Count how many periods have already been posted
        long postedCount = current.stream().filter(DepreciationScheduleLine::isPosted).count();
        int nextVersion = current.stream()
                .mapToInt(DepreciationScheduleLine::getScheduleVersion)
                .max()
                .orElse(0) + 1;

        // Regenerate on the new carrying cost; skip already-posted periods
        // Use the accumulated depreciation already posted (set on the asset) as the starting accum
        generateSchedule(asset, nextVersion, asset.getAccumulatedDepreciation(), createdBy,
                (int) postedCount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepreciationScheduleLineDto> listByAsset(String assetUid) {
        FixedAsset asset = assets.findByUid(assetUid)
                .orElseThrow(() -> NotFoundException.of("FixedAsset", assetUid));
        return schedules.findByFixedAssetIdOrderByScheduleVersionAscPeriodSeqAsc(asset.getId())
                .stream().map(this::toDto).toList();
    }

    // -------------------------------------------------------------------------

    private void generateSchedule(FixedAsset asset, int scheduleVersion,
                                    BigDecimal startingAccum, Long createdBy) {
        generateSchedule(asset, scheduleVersion, startingAccum, createdBy, 0);
    }

    /**
     * Core schedule generator (ADR-0030 D-5 arithmetic).
     *
     * @param asset           the asset
     * @param scheduleVersion the version to stamp on new lines
     * @param startingAccum   accumulated depreciation already posted (0 for first generation)
     * @param createdBy       actor
     * @param skipSeqs        number of leading periods to skip (already posted; seq 1..skipSeqs
     *                        are omitted from the new version)
     */
    private void generateSchedule(FixedAsset asset, int scheduleVersion,
                                    BigDecimal startingAccum, Long createdBy,
                                    int skipSeqs) {
        BigDecimal carryingCost  = asset.getCarryingCost();
        BigDecimal salvage       = asset.getSalvageValue();
        int        n             = asset.getLifePeriods();
        // ADR-0030 D-5 Finding 2 fix: the depreciable BASE for remaining periods must subtract
        // already-posted accumulated depreciation. On first generation startingAccum=0 so behaviour
        // is unchanged. On regeneration after revaluation:
        //   base = new_carrying_cost - salvage - accumulated_depreciation_already_posted
        // Without this subtraction the regenerated schedule over-charges by the accum_dep amount
        // (e.g. 10M - 0 - 1M = 9M is correct; 10M - 0 = 10M incorrectly ignores posted charges).
        BigDecimal base          = carryingCost.subtract(salvage).subtract(startingAccum);
        BigDecimal accum         = startingAccum;

        LocalDate startDate = asset.getDepreciationStartDate();

        // Remaining periods to generate
        int remaining = n - skipSeqs;
        if (remaining <= 0) return;

        // Distribute the base over the REMAINING periods, not the original life.
        // On first generation remaining == n, so this is equivalent. On regeneration after
        // revaluation remaining < n, and dividing by n would under-charge each period
        // (leaving a residual that the final-period plug can never fully clear) — ADR-0030 D-5.
        BigDecimal perSl    = base.divide(BigDecimal.valueOf(remaining), SCALE, RM);
        BigDecimal runningBase = base;  // for SL tracking
        BigDecimal openingNbv = carryingCost.subtract(accum); // for RB

        List<DepreciationScheduleLine> lines = new ArrayList<>(remaining);

        for (int i = 0; i < remaining; i++) {
            int seq = skipSeqs + i + 1;  // 1-based seq
            boolean isFinal = (seq == n);

            BigDecimal charge;
            if (asset.getDepreciationMethod() == DepreciationMethod.STRAIGHT_LINE) {
                if (isFinal) {
                    charge = runningBase; // plug
                } else {
                    charge = min(perSl, runningBase);
                }
                runningBase = runningBase.subtract(charge);
            } else {
                // Reducing balance
                BigDecimal rate   = asset.getReducingRate().divide(BigDecimal.valueOf(100), SCALE + 4, RM);
                BigDecimal raw    = openingNbv.multiply(rate).setScale(SCALE, RM);
                BigDecimal floor  = openingNbv.subtract(salvage);
                if (isFinal) {
                    charge = floor.max(BigDecimal.ZERO);
                } else {
                    charge = min(raw, floor);
                }
                openingNbv = openingNbv.subtract(charge);
            }
            charge = charge.max(BigDecimal.ZERO);

            accum = accum.add(charge);
            BigDecimal nbvAfter = carryingCost.subtract(accum);

            // period_date = start_date shifted by (seq-1) months (BR-FA-11)
            LocalDate periodDate = startDate.plusMonths(seq - 1);

            lines.add(new DepreciationScheduleLine(
                    asset.getId(), asset.getCompanyId(),
                    seq, scheduleVersion, periodDate,
                    charge, accum, nbvAfter, createdBy));
        }

        schedules.saveAll(lines);
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private DepreciationScheduleLineDto toDto(DepreciationScheduleLine s) {
        return new DepreciationScheduleLineDto(
                s.getId(), s.getUid(), s.getFixedAssetId(),
                s.getPeriodSeq(), s.getScheduleVersion(), s.getPeriodDate(),
                s.getPlannedCharge(), s.getAccumulatedAfter(), s.getNbvAfter(),
                s.isPosted(), s.getDepreciationRunId());
    }
}
