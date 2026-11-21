package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.sales.domain.dto.PosSaleReturnedPayload;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code POS.SALE.RETURNED} — puts the returned goods of a POS item return back into stock
 * at their ORIGINAL issue cost (ADR-0072).
 *
 * <p>Mirrors {@link SaleReversalStockHandler} (whole-sale void) and {@link DeliveryReturnStockHandler}
 * (partial return): a positive {@code SALE_REVERSAL} movement per stockable product,
 * {@link InventoryValuationService#reverseIssue} to restore {@code on_hand_value}, and one
 * Dr INVENTORY / Cr COGS journal for the whole return via {@link InventoryGlPoster}.
 *
 * <h2>Which cost</h2>
 * The invoice's own {@code SALE_ISSUE} movements are the record of what was issued and at what cost.
 * A movement is not tied to one invoice line (two lines of one product are two movements, a kit sale
 * issues its components), so the cost per base unit of each product is the invoice's total issued value
 * of that product over its total issued quantity — identical across lines, because an issue never moves
 * the average cost. The returned quantity is valued at that unit cost (4 dp, HALF_UP), the same
 * pro-rating the credit-note path uses. A product with no issue movement on the invoice (non-stockable,
 * or a sale that never issued stock) is skipped with a WARN; one whose issue carried no value moves
 * quantity only, exactly as the void handler does.
 *
 * <h2>Kits and recipes</h2>
 * Decided exactly as at sale time ({@link RecipeExplosionResolver#shouldExplodeAtIssue}): a kit line
 * is exploded for the returned quantity and each stockable component is put back. The current recipe is
 * used; a component the invoice never issued (recipe changed since) is skipped with a WARN.
 *
 * <p>Idempotency: {@link IdempotencyGuard} consumer {@code STOCK.POS_SALE_RETURN}, the per-posting
 * source keys of {@link MovementSourceKeys}, and the {@code uq_stock_movement_source_event} backstop.
 */
@Component
public class PosSaleReturnStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PosSaleReturnStockHandler.class);

    static final String CONSUMER = "STOCK.POS_SALE_RETURN";
    private static final String DOC_TYPE = "POS_SALE_RETURN";

    private final IdempotencyGuard          guard;
    private final StockPostingService       posting;
    private final StockMovementRepository   movementRepository;
    private final ProductService            productService;
    private final RecipeExplosionResolver   explosion;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster         glPoster;
    private final ObjectMapper              objectMapper;
    private final CompanyCalendar           calendar;

    public PosSaleReturnStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     StockMovementRepository movementRepository,
                                     ProductService productService,
                                     RecipeExplosionResolver explosion,
                                     InventoryValuationService valuation,
                                     InventoryGlPoster glPoster,
                                     ObjectMapper objectMapper,
                                     CompanyCalendar calendar) {
        this.guard              = guard;
        this.posting            = posting;
        this.movementRepository = movementRepository;
        this.productService     = productService;
        this.explosion          = explosion;
        this.valuation          = valuation;
        this.glPoster           = glPoster;
        this.objectMapper       = objectMapper;
        this.calendar           = calendar;
    }

    @Override
    public String eventType() {
        return DomainEventType.POS_SALE_RETURNED;
    }

    /** What the invoice issued of one product: total base quantity and total cost (null = unknown). */
    private record Issued(BigDecimal qty, BigDecimal value) {
        BigDecimal unitCost() {
            if (value == null || qty.signum() == 0) {
                return null;
            }
            return value.divide(qty, 4, RoundingMode.HALF_UP);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("PosSaleReturnStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        PosSaleReturnedPayload payload = deserialise(event.getPayload());

        List<StockMovement> issuedMovements = movementRepository
                .findBySourceDocumentUidAndMovementType(payload.invoiceUid(), MovementType.SALE_ISSUE);
        Map<Long, Issued> issued = new HashMap<>();
        for (StockMovement m : issuedMovements) {
            issued.merge(m.getProductId(),
                    new Issued(m.getQuantity().abs(), m.getValueAmount()),
                    (a, b) -> new Issued(a.qty().add(b.qty()),
                            a.value() == null || b.value() == null ? null : a.value().add(b.value())));
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            BigDecimal totalValue = BigDecimal.ZERO;
            MovementSourceKeys keys = MovementSourceKeys.forEvent(event.getUid());

            for (PosSaleReturnedPayload.LineItem line : payload.lines()) {
                ProductDto product = productService.getByUid(line.productUid());
                if (explosion.shouldExplodeAtIssue(line.productUid(), product.stockable())) {
                    for (RecipeExplosionResolver.ExplosionLine c
                            : explosion.explode(line.productUid(), line.qtyReturnedBase())) {
                        totalValue = totalValue.add(putBack(event, payload, c.productId(),
                                c.quantity().abs(), issued, keys));
                    }
                } else if (!product.stockable()) {
                    log.info("PosSaleReturnStockHandler: non-stockable product uid={} on return uid={}"
                            + " — nothing to put back", line.productUid(), payload.returnUid());
                } else {
                    totalValue = totalValue.add(putBack(event, payload, line.productId(),
                            line.qtyReturnedBase(), issued, keys));
                }
            }

            if (totalValue.signum() > 0) {
                String glEntryUid = glPoster.postSaleReversalInNewTx(
                        event.getCompanyId(), event.getBranchId(),
                        calendar.dateOf(event.getCompanyId(), payload.returnedAt()),
                        payload.returnUid(), payload.returnNumber(), "TZS", totalValue);
                if (glEntryUid == null) {
                    log.warn("PosSaleReturnStockHandler: COGS-reversal GL post returned null for "
                            + "return uid={} — GL not configured or period closed (qty still back)",
                            payload.returnUid());
                }
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

    /**
     * Posts one SALE_REVERSAL movement and restores its value.
     *
     * @return the value put back (zero when none was known or the product was not issued)
     */
    private BigDecimal putBack(DomainEvent event, PosSaleReturnedPayload payload, Long productId,
                               BigDecimal qty, Map<Long, Issued> issued, MovementSourceKeys keys) {
        // Allocated before any early return so a skipped posting still consumes its slot.
        String sourceKey = keys.nextFor(productId);
        if (qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Issued was = issued.get(productId);
        if (was == null) {
            log.warn("PosSaleReturnStockHandler: no SALE_ISSUE for productId={} on invoice uid={} "
                    + "(return uid={}) — nothing to put back", productId, payload.invoiceUid(),
                    payload.returnUid());
            return BigDecimal.ZERO;
        }
        // Probe before reverseIssue — it mutates on_hand_value at once, post() would no-op.
        if (posting.alreadyPosted(sourceKey, productId)) {
            return BigDecimal.ZERO;
        }

        BigDecimal unitCost = was.unitCost();
        BigDecimal value = unitCost == null ? null : unitCost.multiply(qty)
                .setScale(4, RoundingMode.HALF_UP);
        if (value != null && value.signum() > 0) {
            valuation.reverseIssue(event.getCompanyId(), event.getBranchId(), productId, value);
        } else {
            value = null;
            log.warn("PosSaleReturnStockHandler: no issue cost for productId={} on invoice uid={} — "
                    + "valuation restore skipped (qty still back)", productId, payload.invoiceUid());
        }

        posting.post(event.getCompanyId(), event.getBranchId(), productId,
                qty,                                  // positive — stock comes back IN
                MovementType.SALE_REVERSAL,
                sourceKey,
                DOC_TYPE, payload.returnUid(),
                null, null,
                payload.returnedAt(),
                null,
                unitCost,
                value);
        return value == null ? BigDecimal.ZERO : value;
    }

    private PosSaleReturnedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PosSaleReturnedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PosSaleReturnedPayload: " + ex.getMessage(), ex);
        }
    }
}
