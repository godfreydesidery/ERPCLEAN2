package com.erp.modules.projects.domain.dto;

/**
 * Entry-time validator for a project tag (ADR-0033 D-3, D-12).
 *
 * <p>Lives in {@code projects.domain.dto} so foreign modules (GL, AP, Sales) can depend on this
 * interface without importing the {@code projects.service} package — the acyclic DTO edge.
 * The implementation {@code ProjectTagResolverImpl} lives in {@code projects.service}.
 *
 * <p>Validates:
 * <ol>
 *   <li>Project uid → id resolved in the same company (BR-PROJ-01).</li>
 *   <li>project_status ∈ {ACTIVE, ON_HOLD} — else rejects (OQ-PROJ-06).</li>
 *   <li>If taskUid is supplied: task belongs to the project.</li>
 * </ol>
 */
public interface ProjectTagResolver {

    /**
     * Resolve and validate a project (+ optional task) uid to internal ids.
     *
     * @param companyId      the caller's company — must match the project's company (BR-PROJ-01)
     * @param projectUid     the project's uid; must not be null
     * @param projectTaskUid the task's uid; may be null (no task required)
     * @return resolved {@link ProjectTag}
     * @throws com.erp.platform.common.api.NotFoundException  if the project or task uid is not found
     * @throws com.erp.platform.common.api.ForbiddenException if the project is in a different company
     * @throws IllegalStateException                          if the project is not open for tagging
     */
    ProjectTag resolve(Long companyId, String projectUid, String projectTaskUid);
}
