package com.erp.api;

import com.erp.modules.projects.domain.dto.ProjectPnlDto;
import com.erp.modules.projects.domain.dto.ProjectWipRowDto;
import com.erp.modules.projects.service.ProjectCostingQuery;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project P&L / WIP costing query endpoints (ADR-0033 D-6, D-9).
 * Gated PROJECTS.COSTING.VIEW.
 */
@RestController
@RequestMapping("/api/v1/project-costing")
public class ProjectCostingController {

    private final ProjectCostingQuery costingQuery;

    public ProjectCostingController(ProjectCostingQuery costingQuery) {
        this.costingQuery = costingQuery;
    }

    @GetMapping("/projects/uid/{projectUid}/pnl")
    @PreAuthorize("@perm.scoped(#projectUid,'project','PROJECTS.COSTING.VIEW')")
    public ProjectPnlDto projectPnl(@PathVariable String projectUid) {
        return costingQuery.projectPnl(projectUid, RequestContext.get());
    }

    @GetMapping("/wip")
    @PreAuthorize("@perm.has('PROJECTS.COSTING.VIEW')")
    public ApiResponse<List<ProjectWipRowDto>> wipReport(@RequestParam Long companyId) {
        return ApiResponse.ok(costingQuery.wipReport(companyId, RequestContext.get()));
    }
}
