package com.erp.api;

import com.erp.modules.fx.domain.dto.FxRevaluationPreviewDto;
import com.erp.modules.fx.domain.dto.FxRevaluationRunDto;
import com.erp.modules.fx.domain.dto.PostFxRevaluationRequest;
import com.erp.modules.fx.service.FxRevaluationRunService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FX revaluation run REST controller (ADR-0036 D-6/D-7).
 * URL base: /api/v1/fx/revaluation-runs
 *
 * <p>All endpoints guarded by FX.REVALUE or FX.EXPOSURE.VIEW permissions.
 * {@code assertCanActIn} on every read (the #1 anti-regression guard).
 */
@RestController
@RequestMapping("/api/v1/fx/revaluation-runs")
public class FxRevaluationRunController {

    private final FxRevaluationRunService runService;
    private final ScopeGuard             scopeGuard;

    public FxRevaluationRunController(FxRevaluationRunService runService, ScopeGuard scopeGuard) {
        this.runService = runService;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Dry-run preview — compute would-be revaluation lines for a company+period, no GL post.
     * {@code GET /api/v1/fx/revaluation-runs/preview?companyId=&fiscalPeriodUid=&spotRateDate=}
     */
    @GetMapping("/preview")
    @PreAuthorize("@perm.has('FX.REVALUE')")
    public ApiResponse<FxRevaluationPreviewDto> preview(
            @RequestParam Long    companyId,
            @RequestParam String  fiscalPeriodUid,
            @RequestParam(required = false) LocalDate spotRateDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(runService.preview(companyId, fiscalPeriodUid, spotRateDate));
    }

    /**
     * Post the revaluation run — persists run + posts GL journal + schedules reversal.
     * Idempotent per (company, fiscal_period).
     * {@code POST /api/v1/fx/revaluation-runs}
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('FX.REVALUE')")
    public ApiResponse<FxRevaluationRunDto> post(
            @Valid @RequestBody PostFxRevaluationRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        return ApiResponse.ok(runService.post(req));
    }

    /**
     * Explicitly trigger the reversal for a run whose next-period was not open at post-time.
     * {@code POST /api/v1/fx/revaluation-runs/uid/{uid}/reverse?reversalDate=}
     */
    @PostMapping("/uid/{uid}/reverse")
    @PreAuthorize("@perm.has('FX.REVALUE')")
    public ApiResponse<FxRevaluationRunDto> reverse(
            @PathVariable String uid,
            @RequestParam LocalDate reversalDate) {
        return ApiResponse.ok(runService.reverse(uid, reversalDate));
    }

    /**
     * List runs for a company (paginated).
     * {@code GET /api/v1/fx/revaluation-runs?companyId=}
     */
    @GetMapping
    @PreAuthorize("@perm.has('FX.EXPOSURE.VIEW')")
    public ApiResponse<List<FxRevaluationRunDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<FxRevaluationRunDto> page = runService.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * Get a single run by uid.
     * {@code GET /api/v1/fx/revaluation-runs/uid/{uid}}
     */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('FX.EXPOSURE.VIEW')")
    public ApiResponse<FxRevaluationRunDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(runService.getByUid(uid));
    }
}
