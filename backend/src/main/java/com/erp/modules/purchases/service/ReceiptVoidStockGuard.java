package com.erp.modules.purchases.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OPN-13 (owner ruling 2026-10-10): a goods receipt cannot be voided once the stock it brought in
 * has partly been sold or used.
 *
 * <p>A void reverses the receipt's whole remaining base quantity ({@code qtyInBase −
 * returnedQtyInBase}) out of the receiving branch. If the branch no longer holds that much of the
 * product, the reversal drives on-hand negative and backs a cost out of a moving average that has
 * since moved on. The user is told to correct the receipt with a purchase return or a stock
 * adjustment instead.
 *
 * <p><b>On-hand</b> is the branch total for the product (every location), read through the stock
 * module's DTO-returning {@link StockReservationService#getAvailability} — the same cross-module
 * read the sales negative-stock guard uses, so no stock entity crosses the boundary. Receipts post
 * to the branch default location, but stock is fungible within a branch.
 *
 * <p><b>Not landed yet.</b> STOCK.RECEIVED is applied asynchronously (outbox, about a second). While
 * this receipt's event is still pending (or parked as failed), its goods are not in on-hand at all
 * and nothing can have been consumed from them; the void's own reversal event is dispatched after
 * it. The check is skipped in that window rather than refusing a perfectly good void.
 */
@Component
public class ReceiptVoidStockGuard {

    private static final Logger log = LoggerFactory.getLogger(ReceiptVoidStockGuard.class);

    private final StockReservationService stock;
    private final ProductRepository       products;
    private final DomainEventRepository   events;

    public ReceiptVoidStockGuard(StockReservationService stock,
                                 ProductRepository products,
                                 DomainEventRepository events) {
        this.stock    = stock;
        this.products = products;
        this.events   = events;
    }

    /**
     * Throws a friendly {@link IllegalStateException} when the receiving branch holds less of any
     * stockable product than voiding this receipt would take out.
     */
    public void assertStockStillOnHand(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
        if (receiptNotYetInStock(gr)) {
            return;
        }
        // Base quantity the void would reverse, per product (one product can sit on two lines).
        Map<Long, BigDecimal> toReverse = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (GoodsReceiptLine l : lines) {
            BigDecimal returned = l.getReturnedQtyInBase() != null ? l.getReturnedQtyInBase() : BigDecimal.ZERO;
            BigDecimal qty = l.getQtyInBase().subtract(returned);
            if (qty.signum() > 0) {
                toReverse.merge(l.getProductId(), qty, BigDecimal::add);
                names.putIfAbsent(l.getProductId(), l.getProductName());
            }
        }
        for (Map.Entry<Long, BigDecimal> e : toReverse.entrySet()) {
            Long productId = e.getKey();
            boolean stockable = products.findByCompanyIdAndId(gr.getCompanyId(), productId)
                    .map(Product::isStockable)
                    .orElse(true);
            if (!stockable) {
                continue;   // services never entered stock, so there is nothing to reverse
            }
            StockAvailabilityDto onHand = stock.getAvailability(gr.getCompanyId(), gr.getBranchId(), productId);
            BigDecimal held = onHand != null && onHand.quantity() != null ? onHand.quantity() : BigDecimal.ZERO;
            if (held.compareTo(e.getValue()) < 0) {
                log.warn("GR void refused (OPN-13) receipt={} productId={}: onHand={} < toReverse={}",
                        gr.getReceiptNumber(), productId, held, e.getValue());
                throw new IllegalStateException(
                        "Some of the goods on this receipt have already been sold or used: only "
                                + display(held.max(BigDecimal.ZERO)) + " of the "
                                + display(e.getValue()) + " " + names.get(productId)
                                + " it brought in (in the stock unit) are still in stock, so it can't "
                                + "be voided. Correct it with a purchase return or a stock adjustment "
                                + "instead.");
            }
        }
    }

    private boolean receiptNotYetInStock(GoodsReceipt gr) {
        return events.existsByAggregateTypeAndAggregateIdAndEventTypeAndStatusIn(
                DomainEventType.AGG_GOODS_RECEIPT, gr.getId(), DomainEventType.STOCK_RECEIVED,
                EnumSet.of(DomainEventStatus.PENDING, DomainEventStatus.FAILED));
    }

    private static String display(BigDecimal qty) {
        BigDecimal stripped = qty.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
