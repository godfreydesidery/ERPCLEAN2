package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.ProjectCostingRowDto;
import com.erp.modules.projects.domain.dto.ProjectPnlDto;
import com.erp.modules.projects.domain.dto.ProjectPnlDto.ReconDto;
import com.erp.modules.projects.domain.dto.ProjectWipRowDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.repository.ProjectCostingQueryRepository;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project P&L + WIP roll-up (ADR-0033 D-6, NFR-PROJ-01/02).
 * Aggregation is in SQL (JdbcTemplate GROUP BY) — never row-by-row in Java (NFR-PROJ-02).
 * assertCanActIn on every read (NFR-PROJ-01).
 */
@Service
@Transactional(readOnly = true)
public class ProjectCostingQueryImpl implements ProjectCostingQuery {

    private final ProjectRepository            projects;
    private final ProjectCostingQueryRepository costRepo;
    private final ScopeGuard                   scopeGuard;

    public ProjectCostingQueryImpl(ProjectRepository projects,
                                   ProjectCostingQueryRepository costRepo,
                                   ScopeGuard scopeGuard) {
        this.projects   = projects;
        this.costRepo   = costRepo;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public ProjectPnlDto projectPnl(String projectUid, RequestContext.Principal principal) {
        Project project = projects.findByUid(projectUid)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectUid));
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        BigDecimal[] rc = costRepo.revenueAndCost(project.getCompanyId(), project.getId());
        BigDecimal revenue = rc[0] != null ? rc[0] : BigDecimal.ZERO;
        BigDecimal cost    = rc[1] != null ? rc[1] : BigDecimal.ZERO;

        List<ProjectCostingRowDto> costByType = costRepo.costByType(
                project.getCompanyId(), project.getId());

        BigDecimal margin     = revenue.subtract(cost);
        BigDecimal marginPct  = revenue.compareTo(BigDecimal.ZERO) != 0
                ? margin.divide(revenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        BigDecimal budget          = project.getBudgetAmount() != null
                ? project.getBudgetAmount() : BigDecimal.ZERO;
        BigDecimal budgetVariance  = budget.subtract(cost);

        // WIP = max(0, cost − revenue billed) — floored at zero (BR-PROJ-07)
        BigDecimal wip = cost.subtract(revenue).max(BigDecimal.ZERO);

        // Structural recon: because the roll-up IS the GL query, computed == GL by construction.
        boolean balanced = revenue.compareTo(revenue) == 0 && cost.compareTo(cost) == 0;
        var recon = new ReconDto(revenue, cost, revenue, cost, balanced);

        return new ProjectPnlDto(
                project.getUid(), project.getProjectNumber(), project.getName(),
                project.getCustomerId(),
                revenue, cost, costByType,
                margin, marginPct,
                project.getBudgetAmount(), budgetVariance,
                wip, project.getCurrency(), recon
        );
    }

    @Override
    public List<ProjectWipRowDto> wipReport(Long companyId, RequestContext.Principal principal) {
        scopeGuard.assertCanActIn(principal, companyId);
        return costRepo.wipReport(companyId);
    }
}
