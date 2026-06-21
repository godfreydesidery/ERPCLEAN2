package com.erp.modules.projects.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.projects.domain.dto.CreateProjectTaskRequest;
import com.erp.modules.projects.domain.dto.ProjectTaskDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.entity.ProjectTask;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.modules.projects.repository.ProjectTaskRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ProjectTaskServiceImpl — CANCELLED/COMPLETED project task creation guard.
 */
class ProjectTaskServiceImplTest {

    private ProjectTaskRepository tasks;
    private ProjectRepository     projects;
    private ScopeGuard            scopeGuard;
    private AuditService          audit;
    private ProjectTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        tasks      = mock(ProjectTaskRepository.class);
        projects   = mock(ProjectRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service    = new ProjectTaskServiceImpl(tasks, projects, scopeGuard, audit);
    }

    // -------------------------------------------------------------------------
    // CANCELLED project must reject task creation
    // -------------------------------------------------------------------------

    @Test
    void create_cancelledProject_throwsConflict() {
        Project cancelled = projectWithStatus(ProjectStatus.CANCELLED);
        when(projects.findByUid("uid-cancelled")).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.create("uid-cancelled", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");

        verify(tasks, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // COMPLETED project must reject task creation
    // -------------------------------------------------------------------------

    @Test
    void create_completedProject_throwsConflict() {
        Project completed = projectWithStatus(ProjectStatus.COMPLETED);
        when(projects.findByUid("uid-done")).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.create("uid-done", validRequest(), principal(1L, 10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");

        verify(tasks, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // ACTIVE project allows task creation
    // -------------------------------------------------------------------------

    @Test
    void create_activeProject_persistsTask() {
        Project active = projectWithStatus(ProjectStatus.ACTIVE);
        when(projects.findByUid("uid-active")).thenReturn(Optional.of(active));

        ProjectTask saved = mock(ProjectTask.class);
        when(saved.getId()).thenReturn(1L);
        when(saved.getUid()).thenReturn("task-uid");
        when(saved.getProjectId()).thenReturn(1L);
        when(saved.getCompanyId()).thenReturn(10L);
        when(saved.getBranchId()).thenReturn(20L);
        when(saved.getTaskCode()).thenReturn("T-001");
        when(saved.getName()).thenReturn("Task One");
        when(saved.isBillable()).thenReturn(true);
        when(tasks.save(any())).thenReturn(saved);

        ProjectTaskDto dto = service.create("uid-active", validRequest(), principal(1L, 10L));

        assertThat(dto).isNotNull();
        assertThat(dto.taskCode()).isEqualTo("T-001");
    }

    // -------------------------------------------------------------------------
    // DRAFT project allows task creation (tasks are created during planning)
    // -------------------------------------------------------------------------

    @Test
    void create_draftProject_persistsTask() {
        Project draft = projectWithStatus(ProjectStatus.DRAFT);
        when(projects.findByUid("uid-draft")).thenReturn(Optional.of(draft));

        ProjectTask saved = mock(ProjectTask.class);
        when(saved.getId()).thenReturn(2L);
        when(saved.getUid()).thenReturn("task-uid-2");
        when(saved.getProjectId()).thenReturn(1L);
        when(saved.getCompanyId()).thenReturn(10L);
        when(saved.getBranchId()).thenReturn(20L);
        when(saved.getTaskCode()).thenReturn("T-002");
        when(saved.getName()).thenReturn("Task Two");
        when(saved.isBillable()).thenReturn(false);
        when(tasks.save(any())).thenReturn(saved);

        ProjectTaskDto dto = service.create("uid-draft", validRequest(), principal(1L, 10L));

        assertThat(dto).isNotNull();
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

    private static CreateProjectTaskRequest validRequest() {
        return new CreateProjectTaskRequest("T-001", "Task One", null, true);
    }

    private static RequestContext.Principal principal(Long userId, Long companyId) {
        return new RequestContext.Principal(userId, "user@test.com", false, companyId, null, null);
    }
}
