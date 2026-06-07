package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code STOCK.RECEIVED} — adds stock on Goods Receipt from Purchases (ADR-0010 D-5).
 *
 * <p>For each line: post a GOODS_RECEIPT movement (+qtyInBase). Non-stockable lines are
 * defensively skipped and recorded (D-3 — Purchases should not emit non-stockable lines; Stock
 * does not trust the producer blindly).
 *
 * <p>Built now so it is ready when Purchases ships in Increment 3 (ADR-0011). No Purchases code is
 * referenced here — the handler deserialises the {@code STOCK.RECEIVED} payload that Purchases will
 * produce, and is wired via the platform DI ({@link DomainEventHandler} contract).
 */
@Component
public class GoodsReceiptStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptStockHandler.class);

    static final String CONSUMER = "STOCK.GOODS_RECEIPT";
    private static final String DOC_TYPE = "GOODS_RECEIPT";

    private final IdempotencyGuard guard;
    private final StockPostingService posting;
    private final ProductService productService;
    private final ObjectMapper objectMapper;

    public GoodsReceiptStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     ProductService productService,
                                     ObjectMapper objectMapper) {
        this.guard          = guard;
        this.posting        = posting;
        this.productService = productService;
        this.objectMapper   = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.STOCK_RECEIVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("GoodsReceiptStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        StockReceivedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            for (StockReceivedPayload.LineItem line : payload.lines()) {
                ProductDto product = productService.getByUid(line.productUid());
                if (!product.stockable()) {
                    log.info("GoodsReceiptStockHandler: skipping non-stockable product uid={} " +
                                    "name='{}' on receipt uid={} (D-3, defensive)",
                            line.productUid(), product.name(), payload.receiptUid());
                    continue;
                }
                posting.post(
                        event.getCompanyId(), event.getBranchId(), line.productId(),
                        line.qtyInBase(),                 // positive: goods-in
                        MovementType.GOODS_RECEIPT,
                        event.getUid(),                   // source_event_uid = this event's uid
                        DOC_TYPE, payload.receiptUid(),
                        null, null,
                        payload.receivedAt(),
                        null);
            }
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private StockReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, StockReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise StockReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
