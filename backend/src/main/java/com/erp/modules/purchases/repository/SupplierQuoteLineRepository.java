package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierQuoteLineRepository extends JpaRepository<SupplierQuoteLine, Long> {

    List<SupplierQuoteLine> findBySupplierQuoteIdOrderByLineNo(Long supplierQuoteId);

    Optional<SupplierQuoteLine> findByUidAndSupplierQuoteId(String uid, Long supplierQuoteId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM SupplierQuoteLine l WHERE l.supplierQuoteId = :quoteId")
    int findMaxLineNo(@Param("quoteId") Long quoteId);

    /**
     * Last-quoted unit price for (company, supplier, product) — supplier-price reference (BR-PROC-09).
     * Native SQL: joins supplier_quote_lines to supplier_quotes via FK without a mapped JPA association.
     */
    @Query(value = """
            SELECT l.unit_price_amount
              FROM supplier_quote_lines l
              JOIN supplier_quotes q ON l.supplier_quote_id = q.id
             WHERE l.company_id  = :companyId
               AND q.supplier_id = :supplierId
               AND l.product_id  = :productId
             ORDER BY q.created_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<BigDecimal> findLastQuotedUnitCost(@Param("companyId") Long companyId,
                                                 @Param("supplierId") Long supplierId,
                                                 @Param("productId") Long productId);
}
