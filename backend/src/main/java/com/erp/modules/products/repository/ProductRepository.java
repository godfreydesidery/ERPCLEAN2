package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByUid(String uid);

    Optional<Product> findByCompanyIdAndUid(Long companyId, String uid);

    Optional<Product> findByCompanyIdAndCode(Long companyId, String code);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Page<Product> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Search by name (case-insensitive contains), code, or any barcode within a company.
     * Contains (not prefix) per the brief — matches mid-name words.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR p.code = :q)
            """)
    Page<Product> search(@Param("companyId") Long companyId,
                         @Param("q") String q,
                         Pageable pageable);

    /**
     * Resolves a product uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (ADR-0007 D-10). Single-column JPQL projection avoids loading the full entity.
     */
    @Query("SELECT p.companyId FROM Product p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
