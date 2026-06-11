package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.BomComponent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BomComponent} lines (ADR-0026 D-3, D-6).
 *
 * <p>All multi-level explosion reads use batch queries keyed by {@code bom_id} sets to avoid N+1
 * (NFR-BOM-02). The {@code ix_bom_components_child} index serves where-used queries.
 */
public interface BomComponentRepository extends JpaRepository<BomComponent, Long> {

    Optional<BomComponent> findByUid(String uid);

    /** All lines for one BOM (the per-level explosion read). */
    List<BomComponent> findByBomIdOrderByLineNo(Long bomId);

    /**
     * Batch fetch: all lines for a set of bom_ids in one query — the O(levels) N+1 fix (D-6).
     */
    @Query("SELECT c FROM BomComponent c WHERE c.bomId IN :bomIds ORDER BY c.bomId, c.lineNo")
    List<BomComponent> findByBomIdIn(@Param("bomIds") List<Long> bomIds);

    /**
     * Where-used (single-level): all BOM component lines that reference a given component product,
     * company-scoped. Served by {@code ix_bom_components_child} (FR-BOM-14, D-6).
     */
    @Query("""
            SELECT c FROM BomComponent c
            WHERE c.componentProductId = :componentProductId
              AND c.companyId = :companyId
            """)
    List<BomComponent> findByComponentProductIdAndCompanyId(
            @Param("componentProductId") Long componentProductId,
            @Param("companyId") Long companyId);

    /** Find a specific component line by bom + component product ids (for duplicate-child check). */
    Optional<BomComponent> findByBomIdAndComponentProductId(Long bomId, Long componentProductId);

    /** Find a component line by uid scoped to a BOM (security: ensure the line belongs to the BOM). */
    Optional<BomComponent> findByUidAndBomId(String uid, Long bomId);

    /** Maximum line_no already used in a BOM (for auto-incrementing line_no). */
    @Query("SELECT COALESCE(MAX(c.lineNo), 0) FROM BomComponent c WHERE c.bomId = :bomId")
    int maxLineNo(@Param("bomId") Long bomId);

    /** Delete all components for a BOM (used when deleting a DRAFT BOM header). */
    void deleteByBomId(Long bomId);
}
