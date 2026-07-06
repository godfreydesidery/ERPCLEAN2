package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.UnitOfMeasure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, Long> {

    Optional<UnitOfMeasure> findByUid(String uid);

    Optional<UnitOfMeasure> findByCompanyIdAndUid(Long companyId, String uid);

    /** Tenant-scoped id lookup — returns empty when the unit exists but belongs to a different company. */
    Optional<UnitOfMeasure> findByCompanyIdAndId(Long companyId, Long id);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Resolve a unit by its user-supplied code within a company (bulk import: base-unit lookup). */
    Optional<UnitOfMeasure> findByCompanyIdAndCode(Long companyId, String code);

    /**
     * Case-insensitive, whitespace-trimmed NAME uniqueness within a company, across ALL statuses —
     * mirrors the DB index {@code uq_unit_company_name_ci}.
     */
    @Query("SELECT COUNT(u) > 0 FROM UnitOfMeasure u WHERE u.companyId = :companyId "
            + "AND LOWER(TRIM(u.name)) = LOWER(TRIM(:name))")
    boolean existsByCompanyIdAndNormalizedName(@Param("companyId") Long companyId,
                                               @Param("name") String name);

    /** As above, excluding the row being updated. */
    @Query("SELECT COUNT(u) > 0 FROM UnitOfMeasure u WHERE u.companyId = :companyId "
            + "AND LOWER(TRIM(u.name)) = LOWER(TRIM(:name)) AND u.id <> :excludeId")
    boolean existsByCompanyIdAndNormalizedNameExcludingId(@Param("companyId") Long companyId,
                                                          @Param("name") String name,
                                                          @Param("excludeId") Long excludeId);

    Page<UnitOfMeasure> findByCompanyId(Long companyId, Pageable pageable);

    List<UnitOfMeasure> findByCompanyId(Long companyId);

    @Query("""
            SELECT u FROM UnitOfMeasure u
            WHERE u.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR u.code = :q)
            """)
    Page<UnitOfMeasure> search(@Param("companyId") Long companyId,
                               @Param("q") String q,
                               Pageable pageable);

    /**
     * Resolves a unit uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (mirror PriceListRepository.findCompanyIdByUid).
     */
    @Query("SELECT u.companyId FROM UnitOfMeasure u WHERE u.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
