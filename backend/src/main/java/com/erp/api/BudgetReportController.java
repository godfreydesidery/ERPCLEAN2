package com.erp.api;

import com.erp.modules.budgeting.domain.dto.DepartmentalActualsDto;
import com.erp.modules.budgeting.domain.dto.VarianceQuery;
import com.erp.modules.budgeting.domain.dto.VarianceReportDto;
import com.erp.modules.budgeting.service.VarianceReportQuery;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget reporting REST controller — variance + departmental actuals (ADR-0034 D-1/D-8, FR-BUD-15/17).
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET /api/v1/budgeting/variance}             — budget-vs-actual variance report</li>
 *   <li>{@code GET /api/v1/budgeting/departmental-actuals} — departmental actuals (no budget join)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/budgeting")
public class BudgetReportController {

    private final VarianceReportQuery varianceQuery;
    private final ScopeGuard scopeGuard;

    public BudgetReportController(VarianceReportQuery varianceQuery, ScopeGuard scopeGuard) {
        this.varianceQuery = varianceQuery;
        this.scopeGuard    = scopeGuard;
    }

    /**
     * Budget-vs-actual variance report (FR-BUD-15/16).
     * ScopeGuard.assertCanActIn is called within VarianceReportQueryImpl.
     */
    @GetMapping("/variance")
    @PreAuthorize("@perm.has('BUDGETING.REPORT.VIEW')")
    public VarianceReportDto variance(
            @RequestParam Long companyId,
            @RequestParam String fiscalYearUid,
            @RequestParam(defaultValue = "1") int fromPeriodNo,
            @RequestParam(defaultValue = "12") int toPeriodNo,
            @RequestParam(required = false) String costCentreValueUid,
            @RequestParam(required = false) AccountType accountType) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return varianceQuery.run(new VarianceQuery(
                companyId, fiscalYearUid, fromPeriodNo, toPeriodNo,
                costCentreValueUid, accountType));
    }

    /**
     * Departmental actuals — GL actuals grouped by cost-centre × account (FR-BUD-17).
     */
    @GetMapping("/departmental-actuals")
    @PreAuthorize("@perm.has('BUDGETING.REPORT.VIEW')")
    public DepartmentalActualsDto departmentalActuals(
            @RequestParam Long companyId,
            @RequestParam String fiscalYearUid,
            @RequestParam(defaultValue = "1") int fromPeriodNo,
            @RequestParam(defaultValue = "12") int toPeriodNo) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return varianceQuery.departmentalActuals(companyId, fiscalYearUid, fromPeriodNo, toPeriodNo);
    }
}
