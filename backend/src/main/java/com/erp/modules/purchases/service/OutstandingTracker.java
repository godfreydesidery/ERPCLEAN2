package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The single funnel for maintaining {@code purchase_order_lines.received_qty_in_base} (ADR-0011 D-3).
 *
 * <p>Both the receive and the void path go through this component — it is the only code that
 * modifies {@code received_qty_in_base}. Called inside the GR receive/void transaction (never
 * in a separate TX). The PO line's optimistic {@code version} (via the header) guards concurrent
 * receipts against the same PO line (NFR-PURCH-07).
 *
 * <p>The DB {@code chk_purchase_order_line_received} CHECK (received <= ordered) is the
 * structural backstop — the service validates outstanding BEFORE calling {@code applyReceipt}.
 */
@Component
public class OutstandingTracker {

    private final PurchaseOrderLineRepository poLines;

    public OutstandingTracker(PurchaseOrderLineRepository poLines) {
        this.poLines = poLines;
    }

    /**
     * On GR receive: adds each GR line's {@code qtyInBase} to its PO line's
     * {@code received_qty_in_base}. Caller has already validated outstanding ≥ qty.
     */
    public void applyReceipt(List<GoodsReceiptLine> grLines) {
        applyDelta(grLines, false);
    }

    /**
     * On GR void: subtracts each GR line's {@code qtyInBase} from its PO line's
     * {@code received_qty_in_base}, restoring outstanding (FR-PURCH-09, ADR-0011 D-3).
     */
    public void reverseReceipt(List<GoodsReceiptLine> grLines) {
        applyDelta(grLines, true);
    }

    // -------------------------------------------------------------------------

    private void applyDelta(List<GoodsReceiptLine> grLines, boolean negate) {
        // Group GR lines by PO line id to batch-fetch and update each PO line once.
        Map<Long, BigDecimal> deltaByPoLine = grLines.stream()
                .collect(Collectors.groupingBy(
                        GoodsReceiptLine::getPurchaseOrderLineId,
                        Collectors.mapping(GoodsReceiptLine::getQtyInBase,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));

        for (Map.Entry<Long, BigDecimal> entry : deltaByPoLine.entrySet()) {
            Long poLineId = entry.getKey();
            BigDecimal delta = negate ? entry.getValue().negate() : entry.getValue();

            PurchaseOrderLine line = poLines.findById(poLineId)
                    .orElseThrow(() -> new IllegalStateException(
                            "PO line not found: " + poLineId + " — outstanding tracker inconsistency"));

            BigDecimal newReceived = line.getReceivedQtyInBase().add(delta);
            // Defensive guard (the DB CHECK is the structural backstop but we want a clear message)
            if (newReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalStateException(
                        "Outstanding tracker: reversing receipt would drive received_qty_in_base below 0 "
                                + "on PO line " + line.getUid() + " (delta=" + delta + ")");
            }
            line.setReceivedQtyInBase(newReceived);
        }
    }
}
