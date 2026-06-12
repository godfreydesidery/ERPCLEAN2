package com.erp.modules.projects.domain.dto;

/**
 * Resolved project tag: internal numeric ids for a project + optional task (ADR-0033 D-3).
 *
 * <p>This record lives in {@code projects.domain.dto} so that foreign modules (GL, AP, Sales)
 * can import the DTO-level edge (the acyclic edge, ADR-0033 D-12) without importing
 * a projects service implementation.
 *
 * <p>Obtain via {@link ProjectTagResolver#resolve}.
 *
 * @param projectId     the resolved project's internal id (never null)
 * @param projectTaskId the resolved task's internal id, or null if no task was specified
 */
public record ProjectTag(Long projectId, Long projectTaskId) {

    /** Convenience factory — project only, no task. */
    public static ProjectTag of(Long projectId) {
        return new ProjectTag(projectId, null);
    }
}
