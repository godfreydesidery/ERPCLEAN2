package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByUid(String uid);

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    List<Role> findAllByOrderByName();

    /** Fetch a role with its permissions eagerly (avoids a lazy-init when serialising to a DTO). */
    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findWithPermissionsByUid(String uid);

    /**
     * P3-5 (ADR-0062), and invariant I-2. NULL-tolerant BY DESIGN: {@code organisation_id IS NULL}
     * marks the thirteen shipped roles, which are global and must stay visible to every tenant. A
     * plain equality here would hide ORG_ADMIN and all twelve operational bundles from everybody —
     * the roles screen would show a customer only the roles they authored themselves, and the ones
     * they actually use would vanish.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM Role r WHERE r.organisationId IS NULL OR r.organisationId = :organisationId "
          + "ORDER BY r.name")
    java.util.List<Role> findVisibleTo(
            @org.springframework.data.repository.query.Param("organisationId") Long organisationId);

    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM Role r WHERE r.uid = :uid "
          + "AND (r.organisationId IS NULL OR r.organisationId = :organisationId)")
    java.util.Optional<Role> findVisibleByUid(
            @org.springframework.data.repository.query.Param("uid") String uid,
            @org.springframework.data.repository.query.Param("organisationId") Long organisationId);

    /**
     * {@link #findVisibleByUid} with permissions eagerly fetched (ADR-0062 P4-1).
     *
     * <p>Exists because {@link #findWithPermissionsByUid} has <b>no</b> tenant predicate, and the
     * role DETAIL read used it. P3-5 scoped the list and the by-uid lookup used by the write paths,
     * but the detail read reached straight past both — so a caller could read another tenant's role
     * together with its full permission set, which is the most revealing thing a role has.
     * Same NULL-tolerance as its sibling: the thirteen shipped roles are global and must resolve for
     * everyone.
     */
    /**
     * Resolve a role BY CODE within the caller's tenant, global roles included (ADR-0062 P4-1c).
     *
     * <p>Returns a LIST, not an Optional, and that is deliberate. Once {@code uq_role_code} is gone
     * (V102) a derived {@code findByCode} returning {@code Optional} throws
     * {@code IncorrectResultSizeDataAccessException} — an HTTP 500 — the moment two rows share a
     * code. R-1 forbids the tenant/global overlap that would produce two rows here, but a lookup on
     * the approvals path must not depend on an invariant enforced elsewhere staying true: if R-1 is
     * ever breached the approval inbox should pick deterministically, not crash.
     *
     * <p>Ordered tenant-first ({@code NULLS LAST}), so a tenant's own role wins over a global one of
     * the same code. That is the right precedence: the tenant authored theirs deliberately.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM Role r WHERE r.code = :code "
          + "AND (r.organisationId IS NULL OR r.organisationId = :organisationId) "
          + "ORDER BY r.organisationId NULLS LAST")
    java.util.List<Role> findVisibleByCode(
            @org.springframework.data.repository.query.Param("code") String code,
            @org.springframework.data.repository.query.Param("organisationId") Long organisationId);

    /** Does a GLOBAL role hold this code? Backs the R-1 service check (ADR-0062 P4-1d). */
    boolean existsByOrganisationIdIsNullAndCode(String code);

    /** Does THIS tenant already use this code? Replaces the global existsByCode on create (P4-1b). */
    boolean existsByOrganisationIdAndCode(Long organisationId, String code);

    /**
     * Tenant roles whose code collides with a global one — the R-1 breach that no index can prevent.
     *
     * <p>Reported at boot rather than blocked, because the breach can be created by US: a release
     * that ships a new global bundle whose code a customer already used. Aborting there would turn a
     * name clash into a failed migration and a failed boot on a live customer's system, caused by a
     * release they did not ask for. Direction 1 — a tenant reaching for a global code — IS blocked,
     * by the service check and V102's trigger.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT t.code FROM Role t WHERE t.organisationId IS NOT NULL AND EXISTS "
          + "(SELECT 1 FROM Role g WHERE g.organisationId IS NULL AND g.code = t.code)")
    java.util.List<String> findTenantCodesCollidingWithGlobal();

    @EntityGraph(attributePaths = "permissions")
    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM Role r WHERE r.uid = :uid "
          + "AND (r.organisationId IS NULL OR r.organisationId = :organisationId)")
    java.util.Optional<Role> findWithPermissionsVisibleByUid(
            @org.springframework.data.repository.query.Param("uid") String uid,
            @org.springframework.data.repository.query.Param("organisationId") Long organisationId);
}
