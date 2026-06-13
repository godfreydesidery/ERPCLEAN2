package com.erp.modules.fx.service;

import com.erp.modules.fx.domain.dto.FxRevaluationPreviewDto;
import com.erp.modules.fx.domain.dto.FxRevaluationRunDto;
import com.erp.modules.fx.domain.dto.PostFxRevaluationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Period-end unrealized FX revaluation run (ADR-0036 D-6).
 *
 * <p>Cloned from {@code DepreciationRunService} (ADR-0030 D-4) with the run-header/run-lines/
 * preview/idempotency/outbox/audit pattern.
 *
 * <ul>
 *   <li>{@link #preview} — dry run: compute would-be lines, no GL post.</li>
 *   <li>{@link #post}    — persist run + post ONE balanced base-currency journal +
 *                          schedule next-period reversal via {@code postReversal}.</li>
 *   <li>{@link #reverse} — explicitly trigger the reversal (fallback when next period was not
 *                          open at post-time).</li>
 *   <li>{@link #getByUid} / {@link #listByCompany} — reads, all guarded by assertCanActIn.</li>
 * </ul>
 */
public interface FxRevaluationRunService {

    /**
     * Dry-run revaluation for the given company + fiscal period.
     * Returns the would-be per-currency adjustment lines. No GL posting.
     * {@code assertCanActIn} enforced.
     */
    FxRevaluationPreviewDto preview(Long companyId, String fiscalPeriodUid, java.time.LocalDate spotRateDate);

    /**
     * Post the revaluation run.
     * Idempotent per (companyId, fiscalPeriodId) — returns the existing run if already posted.
     * Posts ONE balanced base journal under {@code JournalSourceType.FX_REVALUATION} and
     * schedules a next-period reversal via {@code GLPostingService.postReversal}.
     */
    FxRevaluationRunDto post(PostFxRevaluationRequest req);

    /**
     * Explicitly post the reversal for a run whose next-period was not yet open at post-time.
     * Idempotent via {@code existsByReversalOfId}.
     */
    FxRevaluationRunDto reverse(String runUid, java.time.LocalDate reversalDate);

    /** Get a run by uid (assertCanActIn enforced). */
    FxRevaluationRunDto getByUid(String uid);

    /** Paginated list for a company (assertCanActIn enforced). */
    Page<FxRevaluationRunDto> listByCompany(Long companyId, Pageable pageable);
}
