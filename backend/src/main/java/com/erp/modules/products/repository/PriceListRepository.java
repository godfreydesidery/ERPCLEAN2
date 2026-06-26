package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.PriceList;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceListRepository extends JpaRepository<PriceList, Long> {

    Optional<PriceList> findByUid(String uid);

    Optional<PriceList> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Tenant-scoped existence check for the numeric default-price-list FK on customer records. */
    boolean existsByCompanyIdAndId(Long companyId, Long id);

    Page<PriceList> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT pl FROM PriceList pl
            WHERE pl.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(pl.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR pl.code = :q)
            """)
    Page<PriceList> search(@Param("companyId") Long companyId,
                           @Param("q") String q,
                           Pageable pageable);

    /**
     * Resolves a price list uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (ADR-0007 D-10).
     */
    @Query("SELECT pl.companyId FROM PriceList pl WHERE pl.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
