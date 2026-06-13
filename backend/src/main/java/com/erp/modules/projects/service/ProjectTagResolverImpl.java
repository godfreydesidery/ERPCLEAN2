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
                        "Project not found or belongs to a different company: " + projectUid));

        // (b) Must be open for tagging (BR-PROJ-04, OQ-PROJ-06)
        if (!project.getProjectStatus().allowsTagging()) {
            throw new IllegalStateException(
                    "Project " + projectUid + " is not open for tagging (status="
                            + project.getProjectStatus() + "). The tag is rejected. "
                            + "Re-open the project or use a different project.");
        }

        // (c) Resolve task if supplied; must belong to the project
        Long taskId = null;
        if (projectTaskUid != null && !projectTaskUid.isBlank()) {
            ProjectTask task = tasks.findByUidAndProjectId(projectTaskUid, project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "ProjectTask not found or does not belong to project "
                                    + projectUid + ": " + projectTaskUid));
            taskId = task.getId();
        }

        return new ProjectTag(project.getId(), taskId);
    }
}
