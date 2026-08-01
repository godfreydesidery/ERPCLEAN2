package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /**
     * Most recent quote LINE for (company, supplier, product, unit) — the row behind a PO-line cost
     * suggestion. Unlike {@link #findLastQuotedUnitCost} (amount only, unit-agnostic) it returns the
     * whole line, so the caller also gets the currency and the quote date to show as provenance, and
     * it matches on unit_id — a price quoted per carton must never be suggested for a line ordered
     * per piece.
     *
     * <p>supplier_quote_lines has no mapped association to its parent quote (scalar
     * supplierQuoteId, ADR-0027 D-4), so the supplier is matched through a subquery on the header.
     * Ordered by the line's own created_at — stamped when the quote is captured, the same instant
     * the header is. Caller passes a one-row Pageable; the id tiebreaker keeps the ordering total.
     */
    @Query("""
            SELECT l FROM SupplierQuoteLine l
            WHERE l.companyId = :companyId
              AND l.productId = :productId
              AND l.unitId    = :unitId
              AND l.supplierQuoteId IN (
                    SELECT q.id FROM SupplierQuote q
                    WHERE q.companyId = :companyId AND q.supplierId = :supplierId)
            ORDER BY l.createdAt DESC, l.id DESC
            """)
    List<SupplierQuoteLine> findLastQuotedLine(@Param("companyId") Long companyId,
                                                @Param("supplierId") Long supplierId,
                                                @Param("productId") Long productId,
                                                @Param("unitId") Long unitId,
                                                Pageable pageable);
}
