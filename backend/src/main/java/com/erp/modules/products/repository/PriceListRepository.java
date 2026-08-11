package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.PriceList;
import java.util.List;
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

    /** Resolve a price list by its user-supplied code within a company (bulk price import). */
    Optional<PriceList> findByCompanyIdAndCode(Long companyId, String code);

    /** Tenant-scoped existence check for the numeric default-price-list FK on customer records. */
    boolean existsByCompanyIdAndId(Long companyId, Long id);

    /**
     * Company-scoped numeric lookup (ADR-0056) — used instead of bare {@code findById} so a
     * soft-FK'd {@code priceListId} (e.g. {@code CustomerPrice.priceListId} /
     * {@code PriceTier.priceListId}) can never resolve a foreign company's price list
     * (TenantScopingRulesTest).
     */
    Optional<PriceList> findByCompanyIdAndId(Long companyId, Long id);

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
     * Every list in the company currently flagged as the default.
     *
     * <p>A List, not an Optional, and deliberately so: {@code price_lists} carries no partial unique
     * index behind {@code is_default}, and the flag has been settable through create/update since the
     * column shipped, so a company can already hold more than one flagged row. Setting a default
     * clears all of them rather than the first one it happens to find.
     */
    @Query("SELECT pl FROM PriceList pl WHERE pl.companyId = :companyId AND pl.isDefault = true")
    List<PriceList> findDefaultsOfCompany(@Param("companyId") Long companyId);

    /**
     * Resolves a price list uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (ADR-0007 D-10).
     */
    @Query("SELECT pl.companyId FROM PriceList pl WHERE pl.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
