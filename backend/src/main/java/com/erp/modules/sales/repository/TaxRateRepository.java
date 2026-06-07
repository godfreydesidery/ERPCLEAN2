package com.erp.modules.sales.repository;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.TaxRate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    Optional<TaxRate> findByUid(String uid);

    Optional<TaxRate> findByCompanyIdAndVatStatus(Long companyId, VatStatus vatStatus);

    /**
     * Resolves a tax-rate uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * for case "taxrate" (ADR-0008 D-10).
     */
    @Query("SELECT t.companyId FROM TaxRate t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    boolean existsByCompanyIdAndVatStatus(Long companyId, VatStatus vatStatus);
}
