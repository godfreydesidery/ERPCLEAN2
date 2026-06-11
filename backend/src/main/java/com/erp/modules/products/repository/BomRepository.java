package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.enums.BomStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link Bom} headers (ADR-0026 D-2, D-11).
 *
 * <p>All reads are company-scoped where applicable (FR-BOM-19, NFR-BOM-06).
 * The {@link #findCompanyIdByUid} projection is used by {@link com.erp.platform.security.ScopeGuard}
 * for the {@code "bom"} target type (D-11).
 */
public interface BomRepository extends JpaRepository<Bom, Long> {

    Optional<Bom> findByUid(String uid);

    /**
     * Resolves a BOM uid to its owning company id — used by ScopeGuard.companyIdOf("bom", uid).
     */
    @Query("SELECT b.companyId FROM Bom b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Paged list of BOM headers for a company, optionally filtered by parent product uid and status.
     */
    @Query("""
            SELECT b FROM Bom b
            JOIN Product p ON p.id = b.parentProductId
            WHERE b.companyId = :companyId
              AND (:parentProductUid IS NULL OR p.uid = :parentProductUid)
              AND (:status IS NULL OR b.status = :status)
            ORDER BY b.parentProductId, b.versionNo
            """)
    Page<Bom> findByFilter(@Param("companyId") Long companyId,
                           @Param("parentProductUid") String parentProductUid,
                           @Param("status") BomStatus status,
                           Pageable pageable);

    /** All BOM versions for a parent product (by id). */
    List<Bom> findByCompanyIdAndParentProductId(Long companyId, Long parentProductId);

    /** The single ACTIVE version for a parent product (at most one per uq_bom_one_active). */
    Optional<Bom> findByParentProductIdAndStatus(Long parentProductId, BomStatus status);

    /**
     * The BOM version whose effectivity window contains {@code asOfDate}:
     * {@code effective_from <= asOfDate} and {@code effective_to IS NULL OR effective_to > asOfDate}.
     */
    @Query("""
            SELECT b FROM Bom b
            WHERE b.parentProductId = :parentProductId
              AND b.companyId = :companyId
              AND b.effectiveFrom <= :asOfDate
              AND (b.effectiveTo IS NULL OR b.effectiveTo > :asOfDate)
            ORDER BY b.versionNo DESC
            """)
    List<Bom> findEffective(@Param("companyId") Long companyId,
                            @Param("parentProductId") Long parentProductId,
                            @Param("asOfDate") LocalDate asOfDate);

    /**
     * Maximum version_no already allocated for a parent product — used by the service to allocate
     * the next version_no (D-12). Returns 0 when no version exists yet.
     */
    @Query("SELECT COALESCE(MAX(b.versionNo), 0) FROM Bom b WHERE b.parentProductId = :parentProductId")
    int maxVersionNo(@Param("parentProductId") Long parentProductId);

    /**
     * Batch active-BOM lookup: given a set of parent product ids, returns the ACTIVE Bom for each
     * (O(1) query per level in the explosion — NFR-BOM-02, D-6 N+1 fix).
     */
    @Query("SELECT b FROM Bom b WHERE b.companyId = :companyId AND b.parentProductId IN :parentProductIds AND b.status = 'ACTIVE'")
    List<Bom> findActiveByParentProductIdIn(@Param("companyId") Long companyId,
                                             @Param("parentProductIds") List<Long> parentProductIds);

    /**
     * Check if any ACTIVE BOM exists for the given parent product (used by make/buy defaulting,
     * D-3, and the isComposed upgrade in RecipeExplosionResolver, D-7).
     */
    boolean existsByParentProductIdAndStatus(Long parentProductId, BomStatus status);
}
