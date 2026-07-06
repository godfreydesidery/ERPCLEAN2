package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockLocation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockLocation} (ADR-0028 D-4/D-10).
 */
public interface StockLocationRepository extends JpaRepository<StockLocation, Long> {

    Optional<StockLocation> findByUid(String uid);

    /**
     * Company-scoped numeric lookup (TenantScopingRulesTest) — for cross-module/enrichment callers
     * that hold a locationId and the caller's already-established company scope, so a foreign
     * company's row is never loaded (used by van-reconciliation DTO enrichment, ADR-0051 D-8).
     */
    Optional<StockLocation> findByCompanyIdAndId(Long companyId, Long id);

    /**
     * Company-scoped batch lookup (TenantScopingRulesTest) — for enrichment callers that need to
     * label a page of rows carrying several distinct location ids in one query (on-hand list
     * enrichment, busy-day-sim finding: {@code listOnHand} needs locationUid/locationName per row).
     */
    List<StockLocation> findByCompanyIdAndIdIn(Long companyId, List<Long> ids);

    /** Duplicate-guard for create: real unique key is (company_id, code) — uq_stock_location_company_code. */
    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Tenant-scoped paged list for the location management endpoint. */
    Page<StockLocation> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /** All active locations for a branch (branch default lookup + dropdown). */
    List<StockLocation> findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
            Long companyId, Long branchId, com.erp.platform.common.domain.MasterStatus status);

    /** The branch default location (enforced unique by DB partial-unique index). */
    Optional<StockLocation> findByCompanyIdAndBranchIdAndIsDefaultTrue(Long companyId, Long branchId);

    /**
     * One-active-van-per-agent pre-check (ADR-0051 D-8.4) — a friendly duplicate-guard mirroring the
     * DB partial unique {@code uq_stock_location_agent_active}. {@code excludeId} lets an update
     * exclude the location being edited from the check; pass a value no real row can have (e.g. -1)
     * when checking for a brand-new location that has no id yet.
     */
    boolean existsByAgentIdAndStatusAndIdNot(
            Long agentId, com.erp.platform.common.domain.MasterStatus status, Long excludeId);

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT sl.companyId FROM StockLocation sl WHERE sl.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Check whether any on-hand exists at this location (used for deactivation guard). */
    @Query("""
            SELECT COUNT(soh) > 0
            FROM StockOnHand soh
            WHERE soh.locationId = :locationId
              AND soh.quantity <> 0
            """)
    boolean hasNonZeroOnHand(@Param("locationId") Long locationId);
}
