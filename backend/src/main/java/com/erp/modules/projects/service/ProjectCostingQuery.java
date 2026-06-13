package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.ProjectPnlDto;
import com.erp.modules.projects.domain.dto.ProjectWipRowDto;
import com.erp.platform.security.RequestContext;
import java.util.List;

/** Project P&L + WIP roll-up read model (ADR-0033 D-6, FR-PROJ-09/10/11). */
public interface ProjectCostingQuery {

    /**
     * Full project P&L for a single project: revenue, cost (by type), margin,
     * budget variance, WIP, and the structural recon bar.
     */
    ProjectPnlDto projectPnl(String projectUid, RequestContext.Principal principal);

    /**
     * Cross-project WIP report for a company: all projects with tagged GL activity,
     * showing cost incurred, billed, and WIP (cost − billed, floored at zero).
     */
    List<ProjectWipRowDto> wipReport(Long companyId, RequestContext.Principal principal);
}
