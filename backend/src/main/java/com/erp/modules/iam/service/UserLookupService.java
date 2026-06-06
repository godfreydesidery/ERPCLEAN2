package com.erp.modules.iam.service;

/**
 * Minimal read-only cross-module lookup for checking IAM user validity without exposing
 * {@code AppUserRepository} or IAM entities across module boundaries (ADR-0006 D-1/D-5).
 *
 * <p>The parties module uses this interface to validate that an internal agent references an
 * ACTIVE {@code app_user} in the same organisation (BR-PARTY-10). Only the parties module
 * (and other cross-cutting consumers) depend on this interface, not on IAM internals.
 *
 * <p>Implemented by {@link UserLookupServiceImpl} in the IAM service layer.
 */
public interface UserLookupService {

    /**
     * Returns {@code true} iff a user with the given id exists and is currently {@code ACTIVE}.
     *
     * @param userId the internal {@code app_users.id}
     */
    boolean isActiveUser(Long userId);

    /**
     * Returns {@code true} iff a user with the given uid exists and is currently {@code ACTIVE}.
     *
     * @param userUid the {@code app_users.uid} (ULID)
     */
    boolean isActiveUserByUid(String userUid);

    /**
     * Returns {@code true} iff a user with the given id is {@code ACTIVE} AND has at least one
     * branch assignment in a branch that belongs to {@code companyId}.
     *
     * <p>Used for security finding 4: an internal agent must reference staff of the agent's own
     * company (BR-PARTY-10 company-scope). The check uses branch membership as the company-staff
     * signal — the cleanest signal available without a direct user→company FK. A stronger
     * contract change (switching the agent API to user-uid and adding an explicit company check)
     * is noted as a follow-up; this scopes the existing id check proportionately.
     *
     * @param userId    internal {@code app_users.id}
     * @param companyId the agent's company id
     */
    boolean isActiveUserInCompany(Long userId, Long companyId);
}
