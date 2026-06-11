package com.erp.api;

import com.erp.modules.costing.domain.dto.DimensionSlicedTbDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.service.DimensionSlicedReportQuery;
import com.erp.platform.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dimension-sliced trial balance (ADR-0025 D-8, FR-CC-14/16).
 * Requires COSTING.VIEW + GL.VIEW — both checked here.
 */
@RestController
@RequestMapping("/api/v1/costing/reports")
public class DimensionReportController {

    private final DimensionSlicedReportQuery query;

    public DimensionReportController(DimensionSlicedReportQuery query) {
        this.query = query;
    }

    /**
     * Dimension-sliced trial balance.
     *
     * @param companyId the tenant
     * @param slot      COST_CENTRE | DEPARTMENT | DIMENSION_3 | DIMENSION_4
     * @param valueUid  optional: filter to a specific value (null = all values for the slot)
     * @param rollUp    true = include descendant values (FR-CC-16)
     * @param periodId  optional: fiscal period filter (null = all-time)
     */
    @GetMapping("/sliced-trial-balance")
    @PreAuthorize("@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')")
    public ApiResponse<DimensionSlicedTbDto> slicedTrialBalance(
            @RequestParam Long companyId,
            @RequestParam DimensionSlot slot,
            @RequestParam(required = false) String valueUid,
            @RequestParam(defaultValue = "false") boolean rollUp,
            @RequestParam(required = false) Long periodId) {

        return ApiResponse.ok(
                query.slicedTrialBalance(companyId, slot, valueUid, rollUp, periodId));
    }
}
