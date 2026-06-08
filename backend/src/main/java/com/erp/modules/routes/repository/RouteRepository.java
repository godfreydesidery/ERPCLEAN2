package com.erp.modules.routes.repository;

import com.erp.modules.routes.domain.entity.Route;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteRepository extends JpaRepository<Route, Long> {

    Optional<Route> findByUid(String uid);

    Optional<Route> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Page<Route> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Search by name (case-insensitive) or code within a company.
     */
    @Query("""
            SELECT r FROM Route r
            WHERE r.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR r.code = :q)
            """)
    Page<Route> search(@Param("companyId") Long companyId,
                       @Param("q") String q,
                       Pageable pageable);

    /**
     * Resolves a route uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (ADR-0012 D-9). Single-column JPQL projection avoids loading the full entity.
     */
    @Query("SELECT r.companyId FROM Route r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
