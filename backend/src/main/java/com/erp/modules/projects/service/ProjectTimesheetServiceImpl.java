package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.CreateTimesheetRequest;
import com.erp.modules.projects.domain.dto.ProjectTimesheetDto;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.entity.ProjectTimesheet;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.modules.projects.repository.ProjectTaskRepository;
import com.erp.modules.projects.repository.ProjectTimesheetRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectTimesheetServiceImpl implements ProjectTimesheetService {

    private static final String AUDIT_TARGET = "project_timesheets";

    private final ProjectTimesheetRepository timesheets;
    private final ProjectRepository          projects;
    private final ProjectTaskRepository      tasks;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public ProjectTimesheetServiceImpl(ProjectTimesheetRepository timesheets,
                                       ProjectRepository projects,
                                       ProjectTaskRepository tasks,
                                       ScopeGuard scopeGuard,
                                       AuditService audit) {
        this.timesheets = timesheets;
        this.projects   = projects;
        this.tasks      = tasks;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ProjectTimesheetDto record(String projectUid, CreateTimesheetRequest req,
                                      RequestContext.Principal principal) {
        Project project = findProject(projectUid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());

        // BR-PROJ-04: timesheets are only accepted when the project is ACTIVE.
        // DRAFT / ON_HOLD / COMPLETED / CANCELLED all reject (PROJECTS-037 / PROJECTS-038).
        if (project.getProjectStatus() != ProjectStatus.ACTIVE) {
            // BR-PROJ-04: timesheets are only accepted on ACTIVE projects.
            throw new ConflictException(
                    "Timesheets can only be recorded on an ACTIVE project. "
                    + "This project is currently " + project.getProjectStatus() + ".");
        }

        var ts = new ProjectTimesheet(project.getId(), project.getCompanyId(), project.getBranchId(),
                req.userId(), req.workDate(), req.hours(), principal.userId());
        ts.setBillable(req.billable());
        ts.setPlannedRateAmount(req.plannedRateAmount());
        ts.setNotes(req.notes());

        if (req.projectTaskUid() != null && !req.projectTaskUid().isBlank()) {
            var task = tasks.findByUidAndProjectId(req.projectTaskUid(), project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "The specified task was not found on this project."));
            ts.setProjectTaskId(task.getId());
        }

        ts = timesheets.save(ts);

        audit.record(AuditEvent.of(AuditActions.PROJECT_TIMESHEET_RECORD, AUDIT_TARGET,
                ts.getId(), ts.getUid())
                .detail(Map.of("projectUid", projectUid, "hours", req.hours().toPlainString())));

        return toDto(ts);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProjectTimesheetDto> listByProject(String projectUid, Pageable pageable,
                                                   RequestContext.Principal principal) {
        Project project = findProject(projectUid);
        scopeGuard.assertCanActIn(principal, project.getCompanyId());
        return timesheets.findByProjectId(project.getId(), pageable).map(this::toDto);
    }

    // -------------------------------------------------------------------------
    private Project findProject(String uid) {
        return projects.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Project not found."));
    }

    private ProjectTimesheetDto toDto(ProjectTimesheet t) {
        return new ProjectTimesheetDto(
                t.getId(), t.getUid(), t.getProjectId(), t.getProjectTaskId(),
                t.getCompanyId(), t.getBranchId(), t.getUserId(),
                t.getWorkDate(), t.getHours(), t.getOvertimeHours(), t.isBillable(),
                t.getPlannedRateAmount(), t.getNotes(), t.getStatus()
        );
    }
}
