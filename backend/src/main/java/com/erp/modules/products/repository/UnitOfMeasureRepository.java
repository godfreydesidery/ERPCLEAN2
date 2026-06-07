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

    boolean existsByCompanyIdAndCode(Long companyId, String code);

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
