package com.erp.api;

import com.erp.modules.fixedassets.domain.dto.DepreciationRunDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunPreviewDto;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import com.erp.modules.fixedassets.service.DepreciationRunService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
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
 * Depreciation run REST controller (ADR-0030 D-15).
 * URL base: /api/v1/fixed-assets/depreciation-runs
 */
@RestController
@RequestMapping("/api/v1/fixed-assets/depreciation-runs")
public class DepreciationRunController {

    private final DepreciationRunService runService;
    private final ScopeGuard             scopeGuard;

    public DepreciationRunController(DepreciationRunService runService, ScopeGuard scopeGuard) {
        this.runService = runService;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('FA.VIEW')")
    public ApiResponse<List<DepreciationRunDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<DepreciationRunDto> page = runService.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/preview")
    @PreAuthorize("@perm.has('FA.DEPRECIATE')")
    public DepreciationRunPreviewDto preview(
            @RequestParam Long companyId,
            @RequestParam String fiscalPeriodUid) {
        return runService.preview(companyId, fiscalPeriodUid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('FA.DEPRECIATE')")
    public DepreciationRunDto post(@Valid @RequestBody RunDepreciationRequest req) {
        return runService.post(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'depreciationrun','FA.VIEW')")
    public DepreciationRunDto get(@PathVariable String uid) {
        return runService.getByUid(uid);
    }
}
