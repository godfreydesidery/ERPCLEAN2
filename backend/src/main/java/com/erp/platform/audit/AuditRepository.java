package com.erp.platform.audit;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for {@link AuditLog}. Append-only: callers use only inserts (via
 * {@link AuditService}) and reads — no {@code delete}/bulk-update is invoked anywhere (enforced by
 * an ArchUnit rule, ADR-0004 D-5; this interface is not referenced outside {@code com.erp.platform.audit}).
 *
 * <p>Filtered paged search uses {@link JpaSpecificationExecutor}: the predicates are built
 * dynamically in {@code AuditReadService}, adding a clause only for a non-null filter. This avoids
 * the {@code (:p IS NULL OR col = :p)} JPQL form, which leaves Postgres unable to infer the bind
 * type of a null parameter (SQLState 42P18).
 */
public interface AuditRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * "Open item" rows of a company: rows with {@code openAction} on {@code targetType} that have no
     * {@code closeAction} row for the same target uid (unless {@code includeClosed}). The optional
     * detail filters use the empty string as "no filter" — a null bind cannot be typed by Postgres
     * here (SQLState 42P18). {@code postingDate} is an ISO date, so text comparison orders it
     * correctly. Used by the GL posting-exceptions screen (ACC-02).
     */
    @Query(value = """
            SELECT a.* FROM audit_logs a
            WHERE a.company_id = :companyId
              AND a.target_type = :targetType
              AND a.action = :openAction
              AND (:sourceType = '' OR a.detail ->> 'sourceType' = :sourceType)
              AND (:fromDate = '' OR a.detail ->> 'postingDate' >= :fromDate)
              AND (:toDate = '' OR a.detail ->> 'postingDate' <= :toDate)
              AND (:includeClosed = TRUE OR NOT EXISTS (
                    SELECT 1 FROM audit_logs c
                    WHERE c.target_type = a.target_type
                      AND c.target_uid  = a.target_uid
                      AND c.company_id  = a.company_id
                      AND c.action      = :closeAction))
            ORDER BY a.at DESC, a.id DESC
            """,
            countQuery = """
            SELECT count(*) FROM audit_logs a
            WHERE a.company_id = :companyId
              AND a.target_type = :targetType
              AND a.action = :openAction
              AND (:sourceType = '' OR a.detail ->> 'sourceType' = :sourceType)
              AND (:fromDate = '' OR a.detail ->> 'postingDate' >= :fromDate)
              AND (:toDate = '' OR a.detail ->> 'postingDate' <= :toDate)
              AND (:includeClosed = TRUE OR NOT EXISTS (
                    SELECT 1 FROM audit_logs c
                    WHERE c.target_type = a.target_type
                      AND c.target_uid  = a.target_uid
                      AND c.company_id  = a.company_id
                      AND c.action      = :closeAction))
            """,
            nativeQuery = true)
    Page<AuditLog> findOpenItems(@Param("companyId") Long companyId,
                                 @Param("targetType") String targetType,
                                 @Param("openAction") String openAction,
                                 @Param("closeAction") String closeAction,
                                 @Param("sourceType") String sourceType,
                                 @Param("fromDate") String fromDate,
                                 @Param("toDate") String toDate,
                                 @Param("includeClosed") boolean includeClosed,
                                 Pageable pageable);

    /**
     * Whether an OPEN item already exists with the same kind/sourceType/sourceRef detail keys — the
     * de-duplication probe run before recording another GL posting exception for the same source.
     */
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM audit_logs a
              WHERE a.company_id = :companyId
                AND a.target_type = :targetType
                AND a.action = :openAction
                AND a.detail ->> 'kind' = :kind
                AND a.detail ->> 'sourceType' = :sourceType
                AND a.detail ->> 'sourceRef' = :sourceRef
                AND NOT EXISTS (
                      SELECT 1 FROM audit_logs c
                      WHERE c.target_type = a.target_type
                        AND c.target_uid  = a.target_uid
                        AND c.company_id  = a.company_id
                        AND c.action      = :closeAction))
            """, nativeQuery = true)
    boolean existsOpenItem(@Param("companyId") Long companyId,
                           @Param("targetType") String targetType,
                           @Param("openAction") String openAction,
                           @Param("closeAction") String closeAction,
                           @Param("kind") String kind,
                           @Param("sourceType") String sourceType,
                           @Param("sourceRef") String sourceRef);

    /** Rows of one company for the given targets and action (company-scoped, never bare by uid). */
    List<AuditLog> findByCompanyIdAndTargetTypeAndActionAndTargetUidIn(
            Long companyId, String targetType, String action, Collection<String> targetUids);

    /**
     * Serialises concurrent closers of the same open item: a transaction-scoped advisory lock keyed
     * on the item, released at commit/rollback. Selects 1 so the native query has a result row.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
            nativeQuery = true)
    Integer lockItem(@Param("key") String key);
}
