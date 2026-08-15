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
    @EntityGraph(attributePaths = "permissions")
    @org.springframework.data.jpa.repository.Query(
            "SELECT r FROM Role r WHERE r.uid = :uid "
          + "AND (r.organisationId IS NULL OR r.organisationId = :organisationId)")
    java.util.Optional<Role> findWithPermissionsVisibleByUid(
            @org.springframework.data.repository.query.Param("uid") String uid,
            @org.springframework.data.repository.query.Param("organisationId") Long organisationId);
}
