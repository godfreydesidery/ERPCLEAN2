package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.ProjectTag;
import com.erp.modules.projects.domain.dto.ProjectTagResolver;
import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.entity.ProjectTask;
import com.erp.modules.projects.repository.ProjectRepository;
import com.erp.modules.projects.repository.ProjectTaskRepository;
import com.erp.platform.common.api.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and resolves a project (+ optional task) uid to internal ids (ADR-0033 D-3, D-12).
 * Implements the DTO-level interface so foreign modules (GL, AP, Sales) can call this
 * through the {@link ProjectTagResolver} interface without importing the impl.
 */
@Service
@Transactional(readOnly = true)
public class ProjectTagResolverImpl implements ProjectTagResolver {

    private final ProjectRepository projects;
    private final ProjectTaskRepository tasks;

    public ProjectTagResolverImpl(ProjectRepository projects, ProjectTaskRepository tasks) {
        this.projects = projects;
        this.tasks    = tasks;
    }

    @Override
    public ProjectTag resolve(Long companyId, String projectUid, String projectTaskUid) {
        // (a) Resolve project uid → entity; enforce same company (BR-PROJ-01)
        Project project = projects.findByUidAndCompanyId(projectUid, companyId)
                .orElseThrow(() -> new NotFoundException(
                        "Project not found."));

        // (b) Must be open for tagging (BR-PROJ-04, OQ-PROJ-06)
        if (!project.getProjectStatus().allowsTagging()) {
            // BR-PROJ-04: project must be in an open status to accept cost/revenue tags.
            throw new IllegalStateException(
                    "This project is not open for tagging (status: " + project.getProjectStatus()
                    + "). Please re-open the project or select a different one.");
        }

        // (c) Resolve task if supplied; must belong to the project
        Long taskId = null;
        if (projectTaskUid != null && !projectTaskUid.isBlank()) {
            ProjectTask task = tasks.findByUidAndProjectId(projectTaskUid, project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "The specified task was not found on this project."));
            taskId = task.getId();
        }

        return new ProjectTag(project.getId(), taskId);
    }
}
