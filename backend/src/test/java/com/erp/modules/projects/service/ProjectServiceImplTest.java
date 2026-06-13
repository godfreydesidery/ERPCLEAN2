package com.erp.modules.projects.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.projects.domain.dto.CreateProjectRequest;
import com.erp.modules.projects.domain.dto.ProjectDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for ProjectServiceImpl (ADR-0033 D-2).
 * Tests the service layer only — mocked repositories, no Testcontainers.
 */
class ProjectServiceImplTest {

    private ProjectRepository     projects;
    private CompanyRepository     companies;
    private BranchRepository      branches;
    private CustomerRepository    customers;
    private ProjectNumberGenerator numberGen;
    private ScopeGuard            scopeGuard;
    private AuditService          audit;
    private ProjectServiceImpl    service;

    @BeforeEach
    void setUp() {
        projects   = mock(ProjectRepository.class);
        companies  = mock(CompanyRepository.class);
        branches   = mock(BranchRepository.class);
        customers  = mock(CustomerRepository.class);
        numberGen  = mock(ProjectNumberGenerator.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service    = new ProjectServiceImpl(projects, companies, branches, customers,
                numberGen, scopeGuard, audit);
    }

    @Test
    void create_persistsAndReturnsDto() {
        // arrange
        Company company = mockCompany(10L, "COMP-001", "USD");
        Branch  branch  = mockBranch(20L, "BR-001");
        when(companies.findByUid("COMP-001")).thenReturn(Optional.of(company));
        when(branches.findByUid("BR-001")).thenReturn(Optional.of(branch));
        when(numberGen.next(10L)).thenReturn("PRJ-0001");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        when(projects.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateProjectRequest(
                "COMP-001", "BR-001", "Acme Website", null, null, null, null, null, null);

        var principal = principal(1L, 10L);

        // act
        ProjectDto dto = service.create(request, principal);

        // assert
        assertThat(dto.projectNumber()).isEqualTo("PRJ-0001");
        assertThat(dto.name()).isEqualTo("Acme Website");
        assertThat(dto.companyId()).isEqualTo(10L);
        assertThat(dto.projectStatus()).isEqualTo(ProjectStatus.DRAFT);
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void create_unknownCompany_throws() {
        when(companies.findByUid("UNKNOWN")).thenReturn(Optional.empty());

        var request = new CreateProjectRequest(
                "UNKNOWN", "BR-X", "Test", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request, principal(1L, 99L)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Company not found");
    }

    @Test
    void changeStatus_invalidTransition_throws() {
        // DRAFT → COMPLETED is invalid
        Project project = new Project(10L, 20L, "PRJ-0001", "Test", "USD", 1L);
        // project starts DRAFT by default
        when(projects.findByUid("uid-1")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.changeStatus("uid-1", ProjectStatus.COMPLETED, principal(1L, 10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid project status transition");
    }

    @Test
    void changeStatus_draftToActive_succeeds() {
        Project project = new Project(10L, 20L, "PRJ-0001", "Test", "USD", 1L);
        when(projects.findByUid("uid-1")).thenReturn(Optional.of(project));

        ProjectDto dto = service.changeStatus("uid-1", ProjectStatus.ACTIVE, principal(1L, 10L));

        assertThat(dto.projectStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(dto.activatedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Company mockCompany(Long id, String uid, String currency) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getUid()).thenReturn(uid);
        when(c.getBaseCurrency()).thenReturn(currency);
        return c;
    }

    private static Branch mockBranch(Long id, String uid) {
        Branch b = mock(Branch.class);
        when(b.getId()).thenReturn(id);
        when(b.getUid()).thenReturn(uid);
        return b;
    }

    private static RequestContext.Principal principal(Long userId, Long companyId) {
        return new RequestContext.Principal(userId, "user@test.com", false, companyId, null, null);
    }
}
