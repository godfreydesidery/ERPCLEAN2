package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.SupplierQuote;
import com.erp.modules.purchases.domain.enums.SupplierQuoteStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierQuoteRepository extends JpaRepository<SupplierQuote, Long> {

    Optional<SupplierQuote> findByUid(String uid);

    Page<SupplierQuote> findByCompanyId(Long companyId, Pageable pageable);

    List<SupplierQuote> findByRfqId(Long rfqId);

    Page<SupplierQuote> findByRfqId(Long rfqId, Pageable pageable);

    List<SupplierQuote> findByRfqIdAndStatus(Long rfqId, SupplierQuoteStatus status);

    boolean existsByCompanyIdAndQuoteNumber(Long companyId, String quoteNumber);

    /** ScopeGuard target-type projection (ADR-0027 D-10). */
    @Query("SELECT q.companyId FROM SupplierQuote q WHERE q.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
