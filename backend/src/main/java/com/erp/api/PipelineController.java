package com.erp.api;

import com.erp.modules.crm.domain.dto.CrmKpiDto;
import com.erp.modules.crm.domain.dto.ForecastDto;
import com.erp.modules.crm.domain.dto.PipelineSummaryDto;
import com.erp.modules.crm.service.PipelineQuery;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRM Pipeline analytics read controller (ADR-0031 D-11).
 * All endpoints are read-only; scope is enforced inside PipelineQuery.
 * Permission codes seeded in V51__crm.sql.
 */
@RestController
@RequestMapping("/api/v1/crm/pipeline")
public class PipelineController {

    private final PipelineQuery pipelineQuery;

    public PipelineController(PipelineQuery pipelineQuery) {
        this.pipelineQuery = pipelineQuery;
    }

    /**
     * Open opportunities grouped by stage.
     * GET /api/v1/crm/pipeline?companyId=1&branchId=2
     */
    @GetMapping
    @PreAuthorize("@perm.has('CRM.OPPORTUNITY.VIEW')")
    public PipelineSummaryDto pipeline(@RequestParam Long companyId,
                                        @RequestParam Long branchId) {
        return pipelineQuery.pipeline(companyId, branchId);
    }

    /**
     * Weighted forecast for opportunities closing in [from, to].
     * GET /api/v1/crm/pipeline/forecast?companyId=1&branchId=2&from=2026-01-01&to=2026-03-31
     */
    @GetMapping("/forecast")
    @PreAuthorize("@perm.has('CRM.OPPORTUNITY.VIEW')")
    public ForecastDto forecast(@RequestParam Long companyId,
                                 @RequestParam Long branchId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pipelineQuery.forecast(companyId, branchId, from, to);
    }

    /**
     * Win-rate + average cycle-time KPIs for a period.
     * GET /api/v1/crm/pipeline/kpis?companyId=1&branchId=2&from=2026-01-01&to=2026-03-31
     */
    @GetMapping("/kpis")
    @PreAuthorize("@perm.has('CRM.OPPORTUNITY.VIEW')")
    public CrmKpiDto kpis(@RequestParam Long companyId,
                           @RequestParam Long branchId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pipelineQuery.kpis(companyId, branchId, from, to);
    }
}
