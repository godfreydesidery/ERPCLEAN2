package com.erp.modules.costing.repository;

import com.erp.modules.costing.domain.entity.DimensionValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DimensionValueRepository extends JpaRepository<DimensionValue, Long> {

    Optional<DimensionValue> findByUid(String uid);

    Optional<DimensionValue> findByCompanyIdAndUid(Long companyId, String uid);

    Page<DimensionValue> findByDimensionId(Long dimensionId, Pageable pageable);

    List<DimensionValue> findByDimensionIdAndActiveTrue(Long dimensionId);

    /** All descendants of a parent (direct children — service walks recursively). */
    List<DimensionValue> findByParentId(Long parentId);

    /** ScopeGuard case "dimensionvalue" (ADR-0025 D-5). */
    @Query("SELECT v.companyId FROM DimensionValue v WHERE v.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Validate a value for posting: (companyId, dimensionId, isActive) projection.
     * Used by DimensionResolver.validateForPosting and by the GL re-assertion step (D-4, D-7).
     */
    @Query("SELECT v FROM DimensionValue v WHERE v.id = :id AND v.companyId = :companyId")
    Optional<DimensionValue> findByIdAndCompanyId(@Param("id") Long id,
                                                   @Param("companyId") Long companyId);

    /** No-hard-delete guard: true if any journal_lines row references this value (FR-CC-03). */
    @Query(value = """
            SELECT (
                SELECT COUNT(*) FROM journal_lines jl
                WHERE jl.cost_centre_value_id = :valueId
                   OR jl.department_value_id  = :valueId
                   OR jl.dimension3_value_id  = :valueId
                   OR jl.dimension4_value_id  = :valueId
            ) > 0
            """, nativeQuery = true)
    boolean hasPostings(@Param("valueId") Long valueId);
}
