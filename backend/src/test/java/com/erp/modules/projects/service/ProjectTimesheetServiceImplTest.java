package com.erp.modules.projects.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.projects.domain.dto.CreateTimesheetRequest;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.modules.projects.repository.ProjectTaskRepository;
import com.erp.modules.projects.repository.ProjectTimesheetRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ProjectTimesheetServiceImpl — BR-PROJ-04 status guard
 * (PROJECTS-037 / PROJECTS-038).
 */
class ProjectTimesheetServiceImplTest {

    private ProjectTimesheetRepository timesheets;
    private ProjectRepository          projects;
    private ProjectTaskRepository      tasks;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private ProjectTimesheetServiceImpl service;

    @BeforeEach
    void setUp() {
        timesheets = mock(ProjectTimesheetRepository.class);
        projects   = mock(ProjectRepository.class);
        tasks      = mock(ProjectTaskRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service    = new ProjectTimesheetServiceImpl(timesheets, projects, tasks, scopeGuard, audit);
    }

    // -------------------------------------------------------------------------
    // PROJECTS-037: DRAFT project must reject a timesheet (BR-PROJ-04)
    // -------------------------------------------------------------------------

    @Test
    void record_draftProject_throwsConflict() {
        Project project = projectWithStatus(ProjectStatus.DRAFT);
        when(projects.findByUid("uid-draft")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.record("uid-draft", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("DRAFT");

        verify(timesheets, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // PROJECTS-038: COMPLETED project must reject a timesheet (BR-PROJ-04)
    // -------------------------------------------------------------------------

    @Test
    void record_completedProject_throwsConflict() {
        Project project = projectWithStatus(ProjectStatus.COMPLETED);
        when(projects.findByUid("uid-done")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.record("uid-done", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("COMPLETED");

        verify(timesheets, never()).save(any());
    }

    @Test
    void record_cancelledProject_throwsConflict() {
        Project project = projectWithStatus(ProjectStatus.CANCELLED);
        when(projects.findByUid("uid-cancelled")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.record("uid-cancelled", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");

        verify(timesheets, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // ON_HOLD is not ACTIVE — should also be rejected by the guard
    // -------------------------------------------------------------------------

    @Test
    void record_onHoldProject_throwsConflict() {
        Project project = projectWithStatus(ProjectStatus.ON_HOLD);
        when(projects.findByUid("uid-hold")).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.record("uid-hold", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");

        verify(timesheets, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Project projectWithStatus(ProjectStatus status) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(1L);
        when(p.getCompanyId()).thenReturn(10L);
        when(p.getBranchId()).thenReturn(20L);
        when(p.getProjectStatus()).thenReturn(status);
        return p;
    }

    private static CreateTimesheetRequest validRequest() {
        return new CreateTimesheetRequest(
                null, 1L, LocalDate.now(), new BigDecimal("8.00"),
                true, null, null);
    }

    private static RequestContext.Principal principal(Long userId, Long companyId) {
        return new RequestContext.Principal(userId, "user@test.com", false, companyId, null, null);
    }
}
