package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Computes and stores the PO order_total_amount from its ordered lines (ADR-0011 D-5).
 *
 * <p>Σ line_total_amount. Called on every DRAFT mutation (add/edit/remove line) and frozen
 * at place (the ORDERED status freeze makes a recompute after that a no-op in practice).
 * Single Java source of the arithmetic — the same "service-computed-and-stored" discipline
 * as {@code InvoiceTotalsCalculator} (ADR-0008 D-4).
 */
@Component
public class PurchaseOrderTotalsCalculator {

    /**
     * Recomputes {@code order_total_amount} and stores it on the header.
     * Does NOT save — the caller flushes (or lets the TX flush on commit).
     */
    public void recompute(PurchaseOrder po, List<PurchaseOrderLine> lines) {
        BigDecimal total = lines.stream()
                .map(PurchaseOrderLine::getLineTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        po.setOrderTotalAmount(total);
    }
}
