package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByUid(String uid);

    Optional<Product> findByCompanyIdAndUid(Long companyId, String uid);

    /** Tenant-scoped id lookup — returns empty when the product exists but belongs to a different company. */
    Optional<Product> findByCompanyIdAndId(Long companyId, Long id);

    /**
     * Tenant-scoped BATCH uid lookup — the whole point of the batch price read (a POS search page
     * asks for up to ~60 products in one request, not 60 requests). The company predicate is applied
     * in the DB, so a foreign-company uid simply does not come back; the caller never sees it and
     * cannot tell it apart from a uid that does not exist.
     *
     * <p>{@code JOIN FETCH} on the base unit because every returned row is priced in (and labelled
     * with) its base unit when the request names no unit — without the fetch that is one extra
     * lazy-load query per product.
     */
    @Query("SELECT p FROM Product p JOIN FETCH p.baseUnit "
            + "WHERE p.companyId = :companyId AND p.uid IN :uids")
    List<Product> findByCompanyIdAndUidIn(@Param("companyId") Long companyId,
                                          @Param("uids") Collection<String> uids);

    Optional<Product> findByCompanyIdAndCode(Long companyId, String code);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /**
     * Case-insensitive, whitespace-trimmed NAME uniqueness within a company, across ALL statuses —
     * mirrors the DB index {@code uq_product_company_name_ci}. Used to fail fast with a friendly
     * message before the DB constraint fires.
     */
    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE p.companyId = :companyId "
            + "AND LOWER(TRIM(p.name)) = LOWER(TRIM(:name))")
    boolean existsByCompanyIdAndNormalizedName(@Param("companyId") Long companyId,
                                               @Param("name") String name);

    /** As above, excluding the row being updated. */
    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE p.companyId = :companyId "
            + "AND LOWER(TRIM(p.name)) = LOWER(TRIM(:name)) AND p.id <> :excludeId")
    boolean existsByCompanyIdAndNormalizedNameExcludingId(@Param("companyId") Long companyId,
                                                          @Param("name") String name,
                                                          @Param("excludeId") Long excludeId);

    Page<Product> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Search within a company by name (case-insensitive contains), code (case-insensitive
     * contains), or an exact barcode match. Contains (not prefix) on name/code per the brief —
     * matches mid-name words and partial codes; barcode is matched exactly because scans deliver
     * the full symbol. The barcode predicate is an {@code EXISTS} subquery (not a join) so a
     * product with several barcodes is never duplicated in the page and pagination stays correct.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(p.code) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (SELECT 1 FROM ProductBarcode b
                              WHERE b.product = p AND b.barcode = :q))
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
