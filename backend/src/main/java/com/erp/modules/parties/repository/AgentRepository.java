package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.platform.common.domain.MasterStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByUid(String uid);

    Optional<Agent> findByCompanyIdAndUid(Long companyId, String uid);

    /**
     * Company-scoped numeric lookup (TenantScopingRulesTest) — for cross-module callers that hold
     * an agentId and the caller's already-established company scope, so a foreign company's row is
     * never loaded (ADR-0051 D-8.4 van/agent enrichment).
     */
    Optional<Agent> findByCompanyIdAndId(Long companyId, Long id);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Tenant-scoped existence check for the numeric default-agent FK on customer records. */
    boolean existsByCompanyIdAndId(Long companyId, Long id);

    Page<Agent> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(a.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR a.tin = :q
                   OR a.phone = :q
                   OR a.code = :q)
            """)
    Page<Agent> search(@Param("companyId") Long companyId,
                       @Param("q") String q,
                       Pageable pageable);

    @Query("SELECT a.companyId FROM Agent a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Auto-default agent: resolves the ACTIVE internal-agent id for the logged-in user in the given
     * company (FR-SALES-15, ADR-0008 D-6). Returns empty if the user has no ACTIVE INTERNAL agent
     * record here — so an archived internal agent is not silently auto-attached (BR-PARTY-10); the
     * caller then falls back to requiring an explicit agentUid (BR-SALES-06).
     */
    @Query("""
            SELECT a.id FROM Agent a
            WHERE a.companyId = :companyId
              AND a.agentKind = com.erp.modules.parties.domain.enums.AgentKind.INTERNAL
              AND a.appUserId = :appUserId
              AND a.status = com.erp.platform.common.domain.MasterStatus.ACTIVE
            """)
    Optional<Long> findInternalAgentIdByCompanyAndUser(@Param("companyId") Long companyId,
                                                       @Param("appUserId") Long appUserId);

    /**
     * D-5: resolves the ACTIVE internal-agent ENTITY (vs {@link #findInternalAgentIdByCompanyAndUser}
     * which returns only the id) for the "my agent" pre-select endpoint (lets the web pre-select the
     * current user's agent on SO/SI/POS forms). {@code findFirst...OrderByIdAsc} is deliberate — there
     * is no DB uniqueness constraint on {@code app_user_id}, so a plain {@code findBy...} risks
     * {@code NonUniqueResultException}; picking the earliest-created row is a safe, deterministic
     * choice.
     */
    Optional<Agent> findFirstByCompanyIdAndAppUserIdAndAgentKindAndStatusOrderByIdAsc(
            Long companyId, Long appUserId, AgentKind agentKind, MasterStatus status);
}
