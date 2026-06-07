package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code SALE.FINALISED} — deducts stock for each line, with recipe explosion (ADR-0010 D-5/D-8).
 *
 * <p>For each line in the payload:
 * <ul>
 *   <li>Simple stockable product → post one SALE_ISSUE (−qtyInBase).</li>
 *   <li>Composed product (has product_components) → explode to stockable components (D-8);
 *       post SALE_ISSUE for each stockable component; skip non-stockable ones.</li>
 *   <li>Non-stockable, non-composed → skip and record (INFO log; no movement row).</li>
 * </ul>
 *
 * <p>Idempotency: checked via {@link IdempotencyGuard} (primary, ADR-0009 D-6a) + DB backstop
 * {@code uq_stock_movement_source_event (source_event_uid, product_id)} (secondary, D-6b).
 *
 * <p>Runs as the system under the event's company/branch scope (D-5): no JWT, no request principal.
 * A system {@link RequestContext.Principal} is set for the duration of the handle call so that
 * tenant-scoped repository methods resolve correctly. It is cleared after, in a finally block.
 */
@Component
public class SaleIssueStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleIssueStockHandler.class);

    /** Consumer name for the processed_events dedup marker (ADR-0010 D-6). */
    static final String CONSUMER = "STOCK.SALE_ISSUE";

    /** Source document type written on each movement (D-5). */
    private static final String DOC_TYPE = "SALES_INVOICE";

    private final IdempotencyGuard guard;
    private final StockPostingService posting;
    private final ProductService productService;
    private final RecipeExplosionResolver explosion;
    private final ObjectMapper objectMapper;

    public SaleIssueStockHandler(IdempotencyGuard guard,
                                  StockPostingService posting,
                                  ProductService productService,
                                  RecipeExplosionResolver explosion,
                                  ObjectMapper objectMapper) {
        this.guard          = guard;
        this.posting        = posting;
        this.productService = productService;
        this.explosion      = explosion;
        this.objectMapper   = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_FINALISED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        // 1. Primary dedup check (ADR-0009 D-6a).
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SaleIssueStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        // 2. Deserialise payload.
        SaleFinalisedPayload payload = deserialise(event.getPayload());

        // 3. Establish system-scoped RequestContext from event tenant scope (D-5).
        //    Save and restore the previous principal so that callers in the same thread
        //    (e.g. integration tests that set a test principal before calling the dispatcher)
        //    are not left with a cleared context after the handler returns.
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            // 4. Process each line.
            for (SaleFinalisedPayload.LineItem line : payload.lines()) {
                processLine(event, payload, line);
            }
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        // 5. Mark processed (in same TX as movements — atomicity guarantee, ADR-0009 D-6a).
        guard.markProcessed(CONSUMER, event.getUid());
    }

    private void processLine(DomainEvent event, SaleFinalisedPayload payload,
                              SaleFinalisedPayload.LineItem line) {
        ProductDto product = productService.getByUid(line.productUid());

        if (explosion.isComposed(line.productUid())) {
            // Composed product — explode to stockable components (D-8).
            List<RecipeExplosionResolver.ExplosionLine> components =
                    explosion.explode(line.productUid(), line.qtyInBase());
            if (components.isEmpty()) {
                log.info("SaleIssueStockHandler: composed product uid={} has no stockable components; " +
                                "no SALE_ISSUE posted for invoice uid={}",
                        line.productUid(), payload.invoiceUid());
            }
            for (RecipeExplosionResolver.ExplosionLine comp : components) {
                posting.post(
                        event.getCompanyId(), event.getBranchId(), comp.productId(),
                        comp.quantity(),                  // already negated by the resolver
                        MovementType.SALE_ISSUE,
                        event.getUid(),                   // source_event_uid = this event's uid
                        DOC_TYPE, payload.invoiceUid(),
                        null, null,
                        payload.finalisedAt(),
                        null);                            // system: no actor
            }
        } else if (!product.stockable()) {
            // Non-stockable, non-composed — skip and record.
            log.info("SaleIssueStockHandler: skipping non-stockable product uid={} name='{}' " +
                            "on invoice uid={} (BR-STOCK-02, D-3)",
                    line.productUid(), product.name(), payload.invoiceUid());
        } else {
            // Simple stockable product — post one SALE_ISSUE.
            posting.post(
                    event.getCompanyId(), event.getBranchId(), line.productId(),
                    line.qtyInBase().negate(),            // signed delta: out = negative
                    MovementType.SALE_ISSUE,
                    event.getUid(),
                    DOC_TYPE, payload.invoiceUid(),
                    null, null,
                    payload.finalisedAt(),
                    null);
        }
    }

    private SaleFinalisedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, SaleFinalisedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise SaleFinalisedPayload: " + ex.getMessage(), ex);
        }
    }
}
