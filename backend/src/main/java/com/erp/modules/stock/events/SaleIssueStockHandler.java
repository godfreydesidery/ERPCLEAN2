package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
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
 * <p>ADR-0020: after each qty movement, calls {@link InventoryValuationService#costIssue} to
 * debit {@code on_hand_value} at current avg; posts DR COGS (5100) / CR INVENTORY (1300) via
 * {@link InventoryGlPoster#postCogsInNewTx} (REQUIRES_NEW — a GL anomaly never poisons the
 * dispatch TX). If avg_cost is NULL for a product (no prior receipt), COGS leg is skipped with
 * a WARN log; quantity still moves (D-2 edge note).
 *
 * <p>Idempotency: checked via {@link IdempotencyGuard} (primary, ADR-0009 D-6a) + DB backstop
 * {@code uq_stock_movement_source_event (source_event_uid, product_id)} (secondary, D-6b).
 */
@Component
public class SaleIssueStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleIssueStockHandler.class);

    /** Consumer name for the processed_events dedup marker (ADR-0010 D-6). */
    static final String CONSUMER = "STOCK.SALE_ISSUE";

    /** Source document type written on each movement (D-5). */
    private static final String DOC_TYPE = "SALES_INVOICE";

    private final IdempotencyGuard           guard;
    private final StockPostingService        posting;
    private final ProductService             productService;
    private final RecipeExplosionResolver    explosion;
    private final InventoryValuationService  valuation;
    private final InventoryGlPoster          glPoster;
    private final ObjectMapper               objectMapper;

    public SaleIssueStockHandler(IdempotencyGuard guard,
                                  StockPostingService posting,
                                  ProductService productService,
                                  RecipeExplosionResolver explosion,
                                  InventoryValuationService valuation,
                                  InventoryGlPoster glPoster,
                                  ObjectMapper objectMapper) {
        this.guard          = guard;
        this.posting        = posting;
        this.productService = productService;
        this.explosion      = explosion;
        this.valuation      = valuation;
        this.glPoster       = glPoster;
        this.objectMapper   = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_FINALISED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SaleIssueStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        SaleFinalisedPayload payload = deserialise(event.getPayload());

        // ADR-0021 D-6 (belt-and-braces seam guard): SO-sourced invoices carry issuesStock=false
        // because the delivery already issued stock via DELIVERY.CONFIRMED → DeliveryIssueStockHandler.
        // Dedup-mark and return immediately — no stock work for revenue-only invoices.
        if (!payload.issuesStock()) {
            log.info("SaleIssueStockHandler: skipping stock issue for SO-sourced invoice uid={} " +
                             "(issuesStock=false, ADR-0021 D-6)", payload.invoiceUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            List<CogsLeg> cogsLegs = new ArrayList<>();

            for (SaleFinalisedPayload.LineItem line : payload.lines()) {
                processLine(event, payload, line, cogsLegs);
            }

            // One COGS journal per SALE.FINALISED event (one per invoice), all components combined.
            if (!cogsLegs.isEmpty()) {
                LocalDate postingDate = payload.finalisedAt() != null
                        ? payload.finalisedAt().atZone(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.now();
                String glEntryUid = glPoster.postCogsInNewTx(
                        event.getCompanyId(), event.getBranchId(), postingDate,
                        payload.invoiceUid(), payload.invoiceNumber(), "TZS", cogsLegs);
                if (glEntryUid == null) {
                    log.warn("SaleIssueStockHandler: COGS GL post returned null for invoice uid={} " +
                                     "— GL not configured or period closed (qty still moved)",
                            payload.invoiceUid());
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

    private void processLine(DomainEvent event, SaleFinalisedPayload payload,
                              SaleFinalisedPayload.LineItem line, List<CogsLeg> cogsLegs) {
        ProductDto product = productService.getByUid(line.productUid());

        // ADR-0058: explode into components ONLY for a point-of-sale kit recipe (product_components,
        // ADR-0010 D-8) or a NON-stockable phantom assembled via a manufacturing BOM (ADR-0026 D-7).
        // A STOCKABLE finished good whose only recipe is a manufacturing BOM is make-to-stock and is
        // issued as itself: its BOM is a PRODUCTION recipe, already consumed by the work order that
        // received it into stock. Re-exploding it at sale decremented the components a second time and
        // left the finished good's own on-hand untouched (the reported defect).
        if (explosion.shouldExplodeAtIssue(line.productUid(), product.stockable())) {
            List<RecipeExplosionResolver.ExplosionLine> components =
                    explosion.explode(line.productUid(), line.qtyInBase());
            if (components.isEmpty()) {
                log.info("SaleIssueStockHandler: composed product uid={} has no stockable components; " +
                                "no SALE_ISSUE posted for invoice uid={}",
                        line.productUid(), payload.invoiceUid());
            }
            for (RecipeExplosionResolver.ExplosionLine comp : components) {
                processComponent(event, payload, comp, cogsLegs);
            }
        } else if (!product.stockable()) {
            log.info("SaleIssueStockHandler: skipping non-stockable product uid={} name='{}' " +
                            "on invoice uid={} (BR-STOCK-02, D-3)",
                    line.productUid(), product.name(), payload.invoiceUid());
        } else {
            processSimpleLine(event, payload, line, product, cogsLegs);
        }
    }

    /**
     * Issues one exploded BOM component and accumulates a COGS leg.
     * FIX G: skips if issuedMagnitude is zero (guards the unit-cost divide).
     */
    private void processComponent(DomainEvent event, SaleFinalisedPayload payload,
                                   RecipeExplosionResolver.ExplosionLine comp,
                                   List<CogsLeg> cogsLegs) {
        // quantity() is already negated by the resolver (component going out)
        BigDecimal issuedMagnitude = comp.quantity().abs();
        // FIX G: guard zero magnitude — unit-cost re-derivation divides by issuedMagnitude
        if (issuedMagnitude.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("SaleIssueStockHandler: zero issuedMagnitude for component " +
                             "productId={} on invoice uid={} — COGS leg skipped (FIX G)",
                    comp.productId(), payload.invoiceUid());
            return;
        }

        BigDecimal issuedValue = valuation.costIssue(
                event.getCompanyId(), event.getBranchId(), comp.productId(), issuedMagnitude);

        posting.post(
                event.getCompanyId(), event.getBranchId(), comp.productId(),
                comp.quantity(),
                MovementType.SALE_ISSUE,
                event.getUid(),
                DOC_TYPE, payload.invoiceUid(),
                null, null,
                payload.finalisedAt(),
                null,
                issuedValue != null ? issuedValue.divide(issuedMagnitude,
                        4, java.math.RoundingMode.HALF_UP) : null,
                issuedValue);

        if (issuedValue == null) {
            log.warn("SaleIssueStockHandler: avg_cost not established for component " +
                             "productId={} on invoice uid={} — COGS leg skipped (D-2 edge)",
                    comp.productId(), payload.invoiceUid());
        } else {
            // comp.productUid() not available from ExplosionLine; use productId as fallback code.
            cogsLegs.add(new CogsLeg(comp.productId(), comp.productId().toString(), issuedValue));
        }
    }

    /**
     * Issues one simple stockable line and accumulates a COGS leg.
     * FIX G: zero-qty guard delegated to {@link InventoryValuationService#costIssue}.
     */
    private void processSimpleLine(DomainEvent event, SaleFinalisedPayload payload,
                                    SaleFinalisedPayload.LineItem line, ProductDto product,
                                    List<CogsLeg> cogsLegs) {
        BigDecimal issuedQty = line.qtyInBase();
        BigDecimal issuedValue = valuation.costIssue(
                event.getCompanyId(), event.getBranchId(), line.productId(), issuedQty);

        // Unit-cost re-derivation: guard zero qty to avoid ArithmeticException (FIX G).
        BigDecimal unitCost = null;
        if (issuedValue != null && issuedQty.compareTo(BigDecimal.ZERO) != 0) {
            unitCost = issuedValue.divide(issuedQty, 4, java.math.RoundingMode.HALF_UP);
        }

        posting.post(
                event.getCompanyId(), event.getBranchId(), line.productId(),
                issuedQty.negate(),
                MovementType.SALE_ISSUE,
                event.getUid(),
                DOC_TYPE, payload.invoiceUid(),
                null, null,
                payload.finalisedAt(),
                null,
                unitCost,
                issuedValue);

        if (issuedValue == null) {
            log.warn("SaleIssueStockHandler: avg_cost not established for product uid={} " +
                             "on invoice uid={} — COGS leg skipped (D-2 edge)",
                    line.productUid(), payload.invoiceUid());
        } else {
            cogsLegs.add(new CogsLeg(line.productId(),
                    product.code() != null ? product.code() : line.productUid(),
                    issuedValue));
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
