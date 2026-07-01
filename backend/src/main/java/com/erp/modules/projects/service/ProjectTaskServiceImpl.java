package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.CreateProjectTaskRequest;
import com.erp.modules.projects.domain.dto.ProjectTaskDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.entity.ProjectTask;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.modules.projects.repository.ProjectTaskRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectTaskServiceImpl implements ProjectTaskService {

    private static final String AUDIT_TARGET = "project_tasks";

    private final ProjectTaskRepository tasks;
    private final ProjectRepository     projects;
    private final ScopeGuard            scopeGuard;
    private final AuditService          audit;

    public ProjectTaskServiceImpl(ProjectTaskRepository tasks,
                                  ProjectRepository projects,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.tasks      = tasks;
        this.projects   = projects;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ProjectTaskDto create(String projectUid, CreateProjectTaskRequest req,
                                 RequestContext.Principal principal) {
        Project project = findProject(projectUid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        // Guard: tasks cannot be added to a CANCELLED or COMPLETED project.
        if (project.getProjectStatus() == ProjectStatus.CANCELLED
                || project.getProjectStatus() == ProjectStatus.COMPLETED) {
            throw new ConflictException(
                    "Tasks cannot be added to a " + project.getProjectStatus() + " project. "
                    + "The project must be in DRAFT, ACTIVE, or ON_HOLD status.");
        }

        var task = new ProjectTask(project.getId(), project.getCompanyId(), project.getBranchId(),
                req.taskCode(), req.name(), principal.userId());
        task.setPlannedHours(req.plannedHours());
        task.setBillable(req.billable());
        task = tasks.save(task);

        audit.record(AuditEvent.of(AuditActions.PROJECT_TASK_CREATE, AUDIT_TARGET,
                task.getId(), task.getUid())
                .detail(Map.of("taskCode", req.taskCode(), "projectUid", projectUid)));

        return toDto(task);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectTaskDto getByUid(String uid, RequestContext.Principal principal) {
        ProjectTask task = findTask(uid);
        scopeGuard.assertCanActIn(principal, task.getCompanyId());
        return toDto(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectTaskDto> listByProject(String projectUid, MasterStatus status,
                                              RequestContext.Principal principal) {
        Project project = findProject(projectUid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());
        return tasks.findByProjectIdAndStatus(project.getId(),
                status != null ? status : MasterStatus.ACTIVE)
                .stream().map(this::toDto).toList();
    }

    @Override
    public ProjectTaskDto update(String uid, CreateProjectTaskRequest req,
                                 RequestContext.Principal principal) {
        ProjectTask task = findTask(uid);
        scopeGuard.assertCanActIn(principal, task.getCompanyId());

        task.setTaskCode(req.taskCode());
        task.setName(req.name());
        task.setPlannedHours(req.plannedHours());
        task.setBillable(req.billable());
        task.setUpdatedAt(Instant.now());
        task.setUpdatedBy(principal.userId());

        audit.record(AuditEvent.of(AuditActions.PROJECT_TASK_UPDATE, AUDIT_TARGET,
                task.getId(), task.getUid()));

        return toDto(task);
    }

    @Override
    public ProjectTaskDto deactivate(String uid, RequestContext.Principal principal) {
        ProjectTask task = findTask(uid);
        scopeGuard.assertCanActIn(principal, task.getCompanyId());

        task.setStatus(MasterStatus.INACTIVE);
        task.setUpdatedAt(Instant.now());
        task.setUpdatedBy(principal.userId());

        audit.record(AuditEvent.of(AuditActions.PROJECT_TASK_DEACTIVATE, AUDIT_TARGET,
                task.getId(), task.getUid()));

        return toDto(task);
    }

    // -------------------------------------------------------------------------
    private Project findProject(String uid) {
        return projects.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Project not found."));
    }

    private ProjectTask findTask(String uid) {
        return tasks.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Project task not found."));
    }

    private ProjectTaskDto toDto(ProjectTask t) {
        return new ProjectTaskDto(
                t.getId(), t.getUid(), t.getProjectId(),
                t.getCompanyId(), t.getBranchId(),
                t.getTaskCode(), t.getName(), t.getParentId(),
                t.getPlannedHours(), t.getPlannedStartDate(), t.getPlannedEndDate(),
                t.getActualHours(), t.getAssigneeUserId(), t.isBillable(), t.getStatus()
        );
    }
}
