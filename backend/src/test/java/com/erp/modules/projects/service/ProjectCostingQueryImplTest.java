package com.erp.modules.projects.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.projects.domain.dto.ProjectPnlDto;
import com.erp.modules.projects.domain.dto.ProjectWipRowDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.repository.ProjectCostingQueryRepository;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ProjectCostingQueryImpl (ADR-0033 D-6 / findings #1 + #4).
 *
 * <p>Finding #1: recon.balanced must be true (structural, not tautological).
 * <p>Finding #4: branch-level isolation — projectPnl must throw ForbiddenException when the
 *               caller's branchId does not match the project's branchId.
 */
class ProjectCostingQueryImplTest {

    private ProjectRepository            projects;
    private ProjectCostingQueryRepository costRepo;
    private ScopeGuard                   scopeGuard;
    private ProjectCostingQueryImpl      service;

    @BeforeEach
    void setUp() {
        projects   = mock(ProjectRepository.class);
        costRepo   = mock(ProjectCostingQueryRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        service    = new ProjectCostingQueryImpl(projects, costRepo, scopeGuard);
    }

    // -------------------------------------------------------------------------
    // Finding #1: recon.balanced is always true (structural — single GL source)
    // -------------------------------------------------------------------------

    @Test
    void projectPnl_reconBalanced_isAlwaysTrue() {
        Project project = stubProject(10L, 20L, "PRJ-0001", "200.00", "100.00");
        when(projects.findByUid("uid-1")).thenReturn(Optional.of(project));
        // scopeGuard.assertCanActIn is a void method — does nothing by default (mock)
        when(costRepo.costByType(10L, 1L)).thenReturn(List.of());

        RequestContext.Principal principal = principal(1L, 10L, 20L);

        ProjectPnlDto dto = service.projectPnl("uid-1", principal);

        // The recon must always be balanced (structural — same GL query, ADR-0033 D-6)
        assertThat(dto.recon().balanced()).isTrue();
        // Computed and GL values are both populated from the same query
        assertThat(dto.recon().computedRevenue()).isEqualByComparingTo(dto.recon().glRevenue());
        assertThat(dto.recon().computedCost()).isEqualByComparingTo(dto.recon().glCost());
    }

    @Test
    void projectPnl_reconBalanced_trueEvenWhenCostExceedsRevenue() {
        // WIP scenario: cost > revenue
        Project project = stubProject(10L, 20L, "PRJ-0002", "50.00", "300.00");
        when(projects.findByUid("uid-2")).thenReturn(Optional.of(project));
        when(costRepo.costByType(10L, 1L)).thenReturn(List.of());

        ProjectPnlDto dto = service.projectPnl("uid-2", principal(1L, 10L, 20L));

        assertThat(dto.recon().balanced()).isTrue();
        // WIP = max(0, cost − revenue) = max(0, 300 − 50) = 250
        assertThat(dto.wip()).isEqualByComparingTo("250.00");
    }

    @Test
    void projectPnl_wipFlooredAtZero_whenRevenueExceedsCost() {
        // Over-billed: revenue > cost → WIP = 0
        Project project = stubProject(10L, 20L, "PRJ-0003", "500.00", "100.00");
        when(projects.findByUid("uid-3")).thenReturn(Optional.of(project));
        when(costRepo.costByType(10L, 1L)).thenReturn(List.of());

        ProjectPnlDto dto = service.projectPnl("uid-3", principal(1L, 10L, 20L));

        assertThat(dto.wip()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // -------------------------------------------------------------------------
    // Finding #4: branch isolation — caller must match project's branch
    // -------------------------------------------------------------------------

    @Test
    void projectPnl_crossBranch_throwsForbidden() {
        // Project is on branch 20; caller is on branch 99 (different branch, same company)
        Project project = stubProject(10L, 20L, "PRJ-0004", "0.00", "0.00");
        when(projects.findByUid("uid-4")).thenReturn(Optional.of(project));

        RequestContext.Principal crossBranchPrincipal = principal(1L, 10L, 99L);

        assertThatThrownBy(() -> service.projectPnl("uid-4", crossBranchPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void projectPnl_sameBranch_succeeds() {
        Project project = stubProject(10L, 20L, "PRJ-0005", "0.00", "0.00");
        when(projects.findByUid("uid-5")).thenReturn(Optional.of(project));
        when(costRepo.costByType(10L, 1L)).thenReturn(List.of());

        // Should not throw — same branch
        ProjectPnlDto dto = service.projectPnl("uid-5", principal(1L, 10L, 20L));
        assertThat(dto).isNotNull();
    }

    @Test
    void projectPnl_nullBranchPrincipal_noExceptionThrown() {
        // Principal with null branchId (e.g., root/admin cross-branch) must not be blocked
        Project project = stubProject(10L, 20L, "PRJ-0006", "0.00", "0.00");
        when(projects.findByUid("uid-6")).thenReturn(Optional.of(project));
        when(costRepo.costByType(10L, 1L)).thenReturn(List.of());

        RequestContext.Principal noBranchPrincipal = principal(1L, 10L, null);

        // null branchId on principal means "no branch restriction" — must not throw
        ProjectPnlDto dto = service.projectPnl("uid-6", noBranchPrincipal);
        assertThat(dto).isNotNull();
    }

    @Test
    void wipReport_delegatesToRepositoryWithBranchId() {
        Long companyId = 10L;
        Long branchId  = 20L;
        RequestContext.Principal principal = principal(1L, companyId, branchId);

        ProjectWipRowDto row = new ProjectWipRowDto("p1", "PRJ-0001", "Test",
                new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("20"), "USD");
        when(costRepo.wipReport(companyId, branchId)).thenReturn(List.of(row));

        List<ProjectWipRowDto> result = service.wipReport(companyId, principal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).wip()).isEqualByComparingTo("20");
    }

    // -------------------------------------------------------------------------
    // PROJECTS-040: empty result for a company with no tagged GL activity
    // (bogus companyId returns empty list, not 500 — ForbiddenException guards
    //  cross-company access; root cross-company returns empty if no rows)
    // -------------------------------------------------------------------------

    @Test
    void wipReport_noProjectsForCompany_returnsEmptyList() {
        Long companyId = 99L;
        RequestContext.Principal principal = principal(1L, companyId, null);

        when(costRepo.wipReport(companyId, null)).thenReturn(List.of());

        List<ProjectWipRowDto> result = service.wipReport(companyId, principal);

        assertThat(result).isEmpty();
    }

    @Test
    void wipReport_nullBranchPrincipal_passesNullBranchToRepository() {
        // Root/admin cross-branch principal has null branchId — must pass null to repo
        // so the SQL omits the branch filter and returns all branches.
        Long companyId = 10L;
        RequestContext.Principal rootPrincipal = principal(1L, companyId, null);

        when(costRepo.wipReport(companyId, null)).thenReturn(List.of());

        service.wipReport(companyId, rootPrincipal);

        // Verify repo called with null branchId (not 0 or some default)
        org.mockito.Mockito.verify(costRepo).wipReport(companyId, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a mocked Project with the given tenant ids and returns revenue/cost from costRepo.
     */
    private Project stubProject(Long companyId, Long branchId, String projectNumber,
                                String revenue, String cost) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(project.getUid()).thenReturn(projectNumber.toLowerCase().replace("-", ""));
        when(project.getCompanyId()).thenReturn(companyId);
        when(project.getBranchId()).thenReturn(branchId);
        when(project.getProjectNumber()).thenReturn(projectNumber);
        when(project.getName()).thenReturn("Test Project");
        when(project.getCustomerId()).thenReturn(null);
        when(project.getBudgetAmount()).thenReturn(null);
        when(project.getCurrency()).thenReturn(CurrencyCode.of("USD"));

        BigDecimal rev  = new BigDecimal(revenue);
        BigDecimal cst  = new BigDecimal(cost);
        when(costRepo.revenueAndCost(companyId, 1L)).thenReturn(new BigDecimal[]{rev, cst});

        return project;
    }

    private static RequestContext.Principal principal(Long userId, Long companyId, Long branchId) {
        return new RequestContext.Principal(userId, "user@test.com", false, companyId, branchId, null);
    }
}
