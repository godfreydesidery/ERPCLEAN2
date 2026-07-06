package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.dto.TenderSubtotalDto;
import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesInvoicePaymentRepository extends JpaRepository<SalesInvoicePayment, Long> {

    List<SalesInvoicePayment> findByInvoiceId(Long invoiceId);

    /**
     * Resolves a payment uid scoped to its parent invoice — SR finding F16 pattern.
     */
    Optional<SalesInvoicePayment> findByUidAndInvoiceId(String uid, Long invoiceId);

    /**
     * Net CASH tender retained in the till drawer for a POS session — busy-day-simulation
     * bugfix (PosSessionServiceImpl expected-cash formula). Only CASH payments on FINALISED
     * invoices count: card/mobile-money/cheque tenders never enter the physical drawer, so
     * folding them into "expected cash" produced a phantom shortage/overage on every mixed-tender
     * session. Nets over-tender change ({@code amount − change_amount}, BR-SALES-07 — only CASH
     * ever carries a non-zero change_amount).
     */
    @Query("""
            SELECT COALESCE(SUM(p.amount - COALESCE(p.changeAmount, 0)), 0)
            FROM SalesInvoicePayment p
            WHERE p.invoice.posSessionId = :sessionId
              AND p.invoice.status = 'FINALISED'
              AND p.tenderType = 'CASH'
            """)
    BigDecimal sumCashTenderByPosSession(@Param("sessionId") Long sessionId);

    /**
     * Per-tender-type net subtotal for a POS session (X/Z-read breakdown) — FINALISED invoices
     * only. See {@link TenderSubtotalDto} for the netting rule.
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.TenderSubtotalDto(
                       p.tenderType,
                       SUM(p.amount - COALESCE(p.changeAmount, 0)))
            FROM SalesInvoicePayment p
            WHERE p.invoice.posSessionId = :sessionId
              AND p.invoice.status = 'FINALISED'
            GROUP BY p.tenderType
            """)
    List<TenderSubtotalDto> sumByPosSessionGroupedByTender(@Param("sessionId") Long sessionId);
}
