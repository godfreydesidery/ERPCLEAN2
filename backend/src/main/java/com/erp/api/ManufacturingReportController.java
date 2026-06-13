package com.erp.api;

import com.erp.modules.manufacturing.domain.dto.WipReconciliationDto;
import com.erp.modules.manufacturing.service.WipReconQuery;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manufacturing reporting endpoints (ADR-0035 D-6, FR-MFG-14).
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET /api/v1/manufacturing/wip-reconciliation} — WIP balance reconciliation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/manufacturing")
public class ManufacturingReportController {

    private final WipReconQuery wipReconQuery;
    private final ScopeGuard    scopeGuard;

    public ManufacturingReportController(WipReconQuery wipReconQuery, ScopeGuard scopeGuard) {
        this.wipReconQuery = wipReconQuery;
        this.scopeGuard    = scopeGuard;
    }

    /**
     * Compares Σ open WIP from work orders vs the WIP_INVENTORY GL account balance (BR-MFG-05).
     * Both figures are in the company's base currency.
     *
     * @param companyId the company to reconcile for
     */
    @GetMapping("/wip-reconciliation")
    @PreAuthorize("@perm.has('MANUFACTURING.VIEW')")
    public ApiResponse<WipReconciliationDto> wipReconciliation(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(wipReconQuery.reconcile(companyId));
    }
}
