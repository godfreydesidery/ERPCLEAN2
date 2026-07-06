package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.sales.domain.dto.DeliveryConfirmedPayload;
import com.erp.modules.sales.repository.DeliveryLineRepository;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryGlPoster.CogsLeg;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code DELIVERY.CONFIRMED} — issues stock and posts DR COGS / CR INVENTORY
 * for each delivered line at moving-average cost (ADR-0021 D-6).
 *
 * <p>Mirrors {@link SaleIssueStockHandler} almost verbatim: recipe explosion, costIssue,
 * StockPostingService, one COGS journal per delivery. The key difference is:
 * <ul>
 *   <li>source_document_type = "DELIVERY" (not "SALES_INVOICE")</li>
 *   <li>source_document_uid  = deliveryUid</li>
 *   <li>sourceRef for the COGS journal = deliveryUid</li>
 * </ul>
 *
 * <p>After issuing each simple line the handler writes {@code issue_value_amount} back to
 * the {@code delivery_line} row (OQ-SO-05) so that {@code SalesReturnServiceImpl} can
 * pro-rate the original issued cost when a return is created (ADR-0021 D-11, Stage 2).
 *
 * <p>Idempotency: checked via {@link IdempotencyGuard} (consumer "STOCK.DELIVERY_ISSUE") +
 * DB backstop {@code uq_stock_movement_source_event (source_event_uid, product_id)}.
 */
@Component
public class DeliveryIssueStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryIssueStockHandler.class);

    static final String CONSUMER = "STOCK.DELIVERY_ISSUE";
    private static final String DOC_TYPE = "DELIVERY";

    private final IdempotencyGuard          guard;
    private final StockPostingService       posting;
    private final ProductService            productService;
    private final RecipeExplosionResolver   explosion;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster         glPoster;
    private final DeliveryLineRepository    deliveryLineRepo;
    private final ObjectMapper              objectMapper;

    public DeliveryIssueStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     ProductService productService,
                                     RecipeExplosionResolver explosion,
                                     InventoryValuationService valuation,
                                     InventoryGlPoster glPoster,
                                     DeliveryLineRepository deliveryLineRepo,
                                     ObjectMapper objectMapper) {
        this.guard            = guard;
        this.posting          = posting;
        this.productService   = productService;
        this.explosion        = explosion;
        this.valuation        = valuation;
        this.glPoster         = glPoster;
        this.deliveryLineRepo = deliveryLineRepo;
        this.objectMapper     = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.DELIVERY_CONFIRMED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("DeliveryIssueStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        DeliveryConfirmedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            List<CogsLeg> cogsLegs = new ArrayList<>();

            for (DeliveryConfirmedPayload.LineItem line : payload.lines()) {
                processLine(event, payload, line, cogsLegs);
            }

            if (!cogsLegs.isEmpty()) {
                LocalDate postingDate = payload.deliveredAt() != null
                        ? payload.deliveredAt().atZone(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.now();
                String glEntryUid = glPoster.postCogsInNewTx(
                        event.getCompanyId(), event.getBranchId(), postingDate,
                        payload.deliveryUid(), payload.deliveryNumber(), "TZS", cogsLegs);
                if (glEntryUid == null) {
                    log.warn("DeliveryIssueStockHandler: COGS GL post returned null for delivery uid={} " +
                                     "— GL not configured or period closed (qty still moved)",
                            payload.deliveryUid());
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

    private void processLine(DomainEvent event, DeliveryConfirmedPayload payload,
                             DeliveryConfirmedPayload.LineItem line, List<CogsLeg> cogsLegs) {
        ProductDto product = productService.getByUid(line.productUid());

        // ADR-0058: explode only a point-of-sale kit recipe (product_components) or a non-stockable
        // BOM phantom. A stockable finished good whose only recipe is a manufacturing BOM is
        // make-to-stock and is issued as itself (its BOM was consumed at production, not delivery).
        if (explosion.shouldExplodeAtIssue(line.productUid(), product.stockable())) {
            List<RecipeExplosionResolver.ExplosionLine> components =
                    explosion.explode(line.productUid(), line.qtyInBase());
            if (components.isEmpty()) {
                log.info("DeliveryIssueStockHandler: composed product uid={} has no stockable components; " +
                                "no SALE_ISSUE posted for delivery uid={}",
                        line.productUid(), payload.deliveryUid());
            }
            // OQ-SO-05: accumulate component costs and write back to delivery_line so that
            // SalesReturnServiceImpl can pro-rate the original cost for partial returns (ADR-0021 D-11).
            BigDecimal totalComponentValue = BigDecimal.ZERO;
            for (RecipeExplosionResolver.ExplosionLine comp : components) {
                BigDecimal compValue = processComponent(event, payload, comp, cogsLegs);
                if (compValue != null) {
                    totalComponentValue = totalComponentValue.add(compValue);
                }
            }
            // Write accumulated component value to delivery_line (mirrors processSimpleLine lines 228-233)
            final BigDecimal composedIssueValue = totalComponentValue;
            if (composedIssueValue.compareTo(BigDecimal.ZERO) > 0 && line.deliveryLineId() != null) {
                deliveryLineRepo.findById(line.deliveryLineId()).ifPresent(dl -> {
                    dl.setIssueValueAmount(composedIssueValue);
                    dl.setUpdatedAt(Instant.now());
                });
            }
        } else if (!product.stockable()) {
            log.info("DeliveryIssueStockHandler: skipping non-stockable product uid={} on delivery uid={}",
                    line.productUid(), payload.deliveryUid());
        } else {
            processSimpleLine(event, payload, line, product, cogsLegs);
        }
    }

    /**
     * Issues one BOM component, accumulates a COGS leg, and returns the issued value for
     * aggregation back to delivery_line.issue_value_amount (OQ-SO-05).
     *
     * @return the issued value for this component, or {@code null} if avg_cost was not established.
     */
    private BigDecimal processComponent(DomainEvent event, DeliveryConfirmedPayload payload,
                                        RecipeExplosionResolver.ExplosionLine comp,
                                        List<CogsLeg> cogsLegs) {
        BigDecimal issuedMagnitude = comp.quantity().abs();
        if (issuedMagnitude.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("DeliveryIssueStockHandler: zero issuedMagnitude for component productId={} on delivery uid={}",
                    comp.productId(), payload.deliveryUid());
            return null;
        }

        BigDecimal issuedValue = valuation.costIssue(
                event.getCompanyId(), event.getBranchId(), comp.productId(), issuedMagnitude);

        posting.post(
                event.getCompanyId(), event.getBranchId(), comp.productId(),
                comp.quantity(),
                MovementType.SALE_ISSUE,
                event.getUid(),
                DOC_TYPE, payload.deliveryUid(),
                null, null,
                payload.deliveredAt(),
                null,
                issuedValue != null ? issuedValue.divide(issuedMagnitude, 4, RoundingMode.HALF_UP) : null,
                issuedValue);

        if (issuedValue == null) {
            log.warn("DeliveryIssueStockHandler: avg_cost not established for component productId={} " +
                             "on delivery uid={} — COGS leg skipped",
                    comp.productId(), payload.deliveryUid());
        } else {
            cogsLegs.add(new CogsLeg(comp.productId(), comp.productId().toString(), issuedValue));
        }
        return issuedValue;
    }

    private void processSimpleLine(DomainEvent event, DeliveryConfirmedPayload payload,
                                    DeliveryConfirmedPayload.LineItem line, ProductDto product,
                                    List<CogsLeg> cogsLegs) {
        BigDecimal issuedQty = line.qtyInBase();
        BigDecimal issuedValue = valuation.costIssue(
                event.getCompanyId(), event.getBranchId(), line.productId(), issuedQty);

        BigDecimal unitCost = null;
        if (issuedValue != null && issuedQty.compareTo(BigDecimal.ZERO) != 0) {
            unitCost = issuedValue.divide(issuedQty, 4, RoundingMode.HALF_UP);
        }

        posting.post(
                event.getCompanyId(), event.getBranchId(), line.productId(),
                issuedQty.negate(),
                MovementType.SALE_ISSUE,
                event.getUid(),
                DOC_TYPE, payload.deliveryUid(),
                null, null,
                payload.deliveredAt(),
                null,
                unitCost,
                issuedValue);

        if (issuedValue == null) {
            log.warn("DeliveryIssueStockHandler: avg_cost not established for product uid={} " +
                             "on delivery uid={} — COGS leg skipped",
                    line.productUid(), payload.deliveryUid());
        } else {
            cogsLegs.add(new CogsLeg(line.productId(),
                    product.code() != null ? product.code() : line.productUid(),
                    issuedValue));
        }

        // OQ-SO-05: write the issued cost back to delivery_line so that SalesReturnServiceImpl
        // can pro-rate the original cost for partial returns (ADR-0021 D-11, Stage 2).
        if (issuedValue != null && line.deliveryLineId() != null) {
            deliveryLineRepo.findById(line.deliveryLineId()).ifPresent(dl -> {
                dl.setIssueValueAmount(issuedValue);
                dl.setUpdatedAt(Instant.now());
            });
        }
    }

    private DeliveryConfirmedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, DeliveryConfirmedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise DeliveryConfirmedPayload: " + ex.getMessage(), ex);
        }
    }
}
