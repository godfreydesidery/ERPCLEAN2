package com.erp.modules.purchases.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.StockReceivedPayload;
import com.erp.modules.purchases.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLineSerial;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptLineSerialRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods Receipt lifecycle service (ADR-0011, FR-PURCH-01b/02b/07/08/09).
 *
 * <p>Security: {@code scopeGuard.assertCanActIn} on EVERY read path (brief §4a).
 *
 * <p>Outbox: {@code OutboxPublisher.publish} is called INSIDE the receive/void @Transactional
 * method — atomicity invariant (ADR-0009 D-3 / ADR-0011 D-7). Never REQUIRES_NEW.
 *
 * <p>Outstanding: {@code OutstandingTracker} updates {@code received_qty_in_base} in the same TX.
 * PO status is recomputed via {@code PurchaseOrderServiceImpl.recomputePoStatus} at end of each
 * receive/void (ADR-0011 D-4).
 */
@Service
@Transactional
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptServiceImpl.class);

    /** Scale of every quantity-in-base column ({@code numeric(19,6)}). */
    private static final int BASE_QTY_SCALE = 6;
    /** Scale of every money column ({@code numeric(19,4)}), including {@code stock_movements.unit_cost_amount}. */
    private static final int MONEY_SCALE = 4;
    /**
     * One unit in the last place of a base quantity. A conversion that lands at most this far
     * ABOVE the outstanding remainder is a rounding artefact of the unit conversion, not a genuine
     * over-receipt, and is snapped onto the remainder (see {@link #buildGrLine}).
     */
    private static final BigDecimal BASE_QTY_ULP = new BigDecimal("0.000001");

    private final GoodsReceiptRepository           receipts;
    private final GoodsReceiptLineRepository       grLines;
    private final GoodsReceiptLineSerialRepository grLineSerials;
    private final PurchaseOrderRepository          orders;
    private final PurchaseOrderLineRepository      poLines;
    private final ProductRepository                products;
    private final PurchaseSettingsRepository       purchaseSettings;
    private final PurchaseNumberGenerator          numberGen;
    private final OutstandingTracker               tracker;
    private final PurchaseOrderServiceImpl         poService;  // for recomputePoStatus
    private final ScopeGuard                       scopeGuard;
    private final AuditService                     audit;
    private final OutboxPublisher                  outbox;
    private final GoodsReceiptPrintQuery           printQuery;

    public GoodsReceiptServiceImpl(GoodsReceiptRepository receipts,
                                   GoodsReceiptLineRepository grLines,
                                   GoodsReceiptLineSerialRepository grLineSerials,
                                   PurchaseOrderRepository orders,
                                   PurchaseOrderLineRepository poLines,
                                   ProductRepository products,
                                   PurchaseSettingsRepository purchaseSettings,
                                   PurchaseNumberGenerator numberGen,
                                   OutstandingTracker tracker,
                                   PurchaseOrderServiceImpl poService,
                                   ScopeGuard scopeGuard,
                                   AuditService audit,
                                   OutboxPublisher outbox,
                                   GoodsReceiptPrintQuery printQuery) {
        this.receipts      = receipts;
        this.grLines       = grLines;
        this.grLineSerials = grLineSerials;
        this.orders        = orders;
        this.poLines       = poLines;
        this.products      = products;
        this.purchaseSettings = purchaseSettings;
        this.numberGen     = numberGen;
        this.tracker       = tracker;
        this.poService     = poService;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
        this.outbox        = outbox;
        this.printQuery    = printQuery;
    }

    // -------------------------------------------------------------------------
    // Create + Receive (combined in one call per ADR-0011 service design)
    // -------------------------------------------------------------------------

    @Override
    public GoodsReceiptDto createAndReceive(CreateGoodsReceiptRequest req) {
        // 1. Resolve PO (company-scoped F15 — purchaseOrderUid gates on purchaseorder target)
        PurchaseOrder po = requireOrderByUid(req.purchaseOrderUid());
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());

        // 2. PO must be ORDERED or PARTIALLY_RECEIVED (not DRAFT/CLOSED/VOID)
        if (po.getStatus() != PurchaseOrderStatus.ORDERED
                && po.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new IllegalStateException(
                    "Can only receive against an ORDERED or PARTIALLY_RECEIVED PO; "
                            + "current PO status: " + po.getStatus());
        }

        // 3. Create the GR header (inherits company/branch/supplier from PO)
        GoodsReceipt gr = new GoodsReceipt(
                po.getCompanyId(), po.getBranchId(),
                po.getId(), po.getSupplierId(),
                actorId());
        gr.setNotes(req.notes());
        GoodsReceipt saved = receipts.save(gr);

        // 4. Validate and create GR lines; reject over-receipt before ANY update (BR-PURCH-10)
        List<GoodsReceiptLine> savedLines = new ArrayList<>();
        // FR-PURCH-07: a goods receipt must contain at least one line
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalArgumentException(
                    "A goods receipt must have at least one line.");
        }
        for (GoodsReceiptLineRequest lineReq : req.lines()) {
            GoodsReceiptLine grLine = buildGrLine(saved, po, lineReq);
            GoodsReceiptLine persisted = grLines.save(grLine);
            persistSerials(persisted, lineReq);
            savedLines.add(persisted);
        }

        // 5. Assign GRN number (inside this TX, ADR-0011 D-6)
        String receiptNumber = numberGen.nextGoodsReceipt(po.getCompanyId());
        saved.setReceiptNumber(receiptNumber);
        saved.setStatus(GoodsReceiptStatus.RECEIVED);
        Instant receivedAt = Instant.now();
        saved.setReceivedAt(receivedAt);
        saved.setReceivedBy(actorId());
        saved.setUpdatedAt(Instant.now());
        saved.setUpdatedBy(actorId());

        // 6. Update PO line received_qty_in_base (OutstandingTracker, same TX, ADR-0011 D-3)
        tracker.applyReceipt(savedLines);

        // 7. Recompute PO status (ADR-0011 D-4)
        poService.recomputePoStatus(po);

        // 8. Emit STOCK.RECEIVED inside the same TX (ADR-0011 D-7; NEVER REQUIRES_NEW)
        List<StockReceivedPayload.LineItem> payloadLines = buildPayloadLines(savedLines);
        outbox.publish(
                DomainEventType.STOCK_RECEIVED,
                DomainEventType.AGG_GOODS_RECEIPT,
                saved.getId(),
                saved.getUid(),
                saved.getCompanyId(),
                saved.getBranchId(),
                new StockReceivedPayload(
                        saved.getUid(),
                        saved.getCompanyId(),
                        saved.getBranchId(),
                        receivedAt,
                        payloadLines,
                        receiptNumber));

        // 9. Audit (ADR-0011 D-13)
        audit.record(AuditEvent.of(AuditActions.PURCHASE_GR_RECEIVE, "goods_receipts",
                        saved.getId(), saved.getUid())
                .detail(Map.of(
                        "receiptNumber", receiptNumber,
                        "purchaseOrderUid", req.purchaseOrderUid(),
                        "lineCount", String.valueOf(savedLines.size()))));

        return toDto(saved, savedLines);
    }

    // -------------------------------------------------------------------------
    // Read paths
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public GoodsReceiptDto getByUid(String uid) {
        GoodsReceipt gr = requireReceipt(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), gr.getCompanyId());
        List<GoodsReceiptLine> lineList = grLines.findByGoodsReceiptIdOrderByLineNo(gr.getId());
        return toDto(gr, lineList);
    }

    /**
     * K9 — the printed note's read model. Delegated whole to {@link GoodsReceiptPrintQuery}, which
     * resolves the derived columns (selling price, previous cost, margin, VAT bands) in native SQL
     * and applies its own scope check.
     */
    @Override
    @Transactional(readOnly = true)
    public GoodsReceiptPrintDto printByUid(String uid) {
        return printQuery.byUid(uid);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GoodsReceiptDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return receipts.search(companyId, q, pageable).map(gr -> {
                List<GoodsReceiptLine> lineList = grLines.findByGoodsReceiptIdOrderByLineNo(gr.getId());
                return toDto(gr, lineList);
            });
        }
        return receipts.findByCompanyId(companyId, pageable).map(gr -> {
            List<GoodsReceiptLine> lineList = grLines.findByGoodsReceiptIdOrderByLineNo(gr.getId());
            return toDto(gr, lineList);
        });
    }

    // -------------------------------------------------------------------------
    // Void
    // -------------------------------------------------------------------------

    @Override
    public GoodsReceiptDto voidReceipt(String uid, VoidGoodsReceiptRequest req) {
        // PROCURE-RECEIVING: null/blank reason must be rejected before any Map.of call (NPE guard)
        // FR-PURCH-09: a reason is mandatory when voiding a goods receipt
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("A reason is required to void a goods receipt.");
        }

        GoodsReceipt gr = requireReceipt(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), gr.getCompanyId());
        if (gr.getStatus() != GoodsReceiptStatus.RECEIVED) {
            throw new IllegalStateException(
                    "Only RECEIVED receipts can be voided; current status: " + gr.getStatus());
        }

        // 1. Transition to VOID
        gr.setStatus(GoodsReceiptStatus.VOID);
        gr.setVoidedAt(Instant.now());
        gr.setVoidedBy(actorId());
        gr.setVoidReason(req.reason());
        gr.setUpdatedAt(Instant.now());
        gr.setUpdatedBy(actorId());

        // 2. Reverse PO line outstanding (OutstandingTracker, same TX, ADR-0011 D-3)
        List<GoodsReceiptLine> lineList = grLines.findByGoodsReceiptIdOrderByLineNo(gr.getId());
        tracker.reverseReceipt(lineList);

        // 3. Recompute PO status (ADR-0011 D-4)
        PurchaseOrder po = requireOrderById(gr.getPurchaseOrderId());
        poService.recomputePoStatus(po);

        // 4. Emit STOCK.RECEIPT.VOIDED inside the same TX (ADR-0011 D-7; NEVER REQUIRES_NEW)
        // D-2: lot/serial fields populated exactly like the forward buildPayloadLines() so the
        // reversal handler can back out stock_batches/stock_serials symmetrically.
        // D-2 FIX B: lotTracked populated from the Product so the reversal handler can target the
        // "UNTRACKED" sentinel batch even when the line itself carries no lot/expiry data.
        List<StockReceiptVoidedPayload.LineItem> payloadLines = lineList.stream()
                .map(l -> {
                    List<String> serials = grLineSerials.findByGoodsReceiptLineId(l.getId())
                            .stream().map(s -> s.getSerialNumber()).toList();
                    return new StockReceiptVoidedPayload.LineItem(
                            l.getProductId(),
                            resolveProductUid(l.getProductId()),
                            l.getUnitId(),
                            l.getQtyInBase(),
                            l.getLotNumber(),
                            l.getManufactureDate(),
                            l.getExpiryDate(),
                            serials,
                            resolveLotTracked(gr.getCompanyId(), l.getProductId()));
                })
                .toList();
        outbox.publish(
                DomainEventType.STOCK_RECEIPT_VOIDED,
                DomainEventType.AGG_GOODS_RECEIPT,
                gr.getId(),
                gr.getUid(),
                gr.getCompanyId(),
                gr.getBranchId(),
                new StockReceiptVoidedPayload(
                        gr.getUid(),
                        gr.getCompanyId(),
                        gr.getBranchId(),
                        payloadLines,
                        gr.getReceiptNumber()));

        // 5. Audit (ADR-0011 D-13)
        audit.record(AuditEvent.of(AuditActions.PURCHASE_GR_VOID, "goods_receipts",
                        gr.getId(), gr.getUid())
                .detail(Map.of(
                        "receiptNumber", gr.getReceiptNumber(),
                        "voidReason", req.reason())));

        return toDto(gr, lineList);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private GoodsReceiptLine buildGrLine(GoodsReceipt gr, PurchaseOrder po,
                                          GoodsReceiptLineRequest lineReq) {
        // Resolve PO line (child-by-parent F16 — lineUid scoped under PO)
        PurchaseOrderLine poLine = poLines.findByUidAndPurchaseOrderId(
                        lineReq.purchaseOrderLineUid(), po.getId())
                .orElseThrow(() -> new NotFoundException(
                        "The specified purchase order line was not found on this purchase order."));

        // Validate received qty > 0
        if (lineReq.receivedQty() == null || lineReq.receivedQty().compareTo(BigDecimal.ZERO) <= 0) {
            // lineReq.purchaseOrderLineUid() intentionally not surfaced (error-hygiene rule)
            throw new IllegalArgumentException(
                    "Received quantity must be greater than zero.");
        }

        // Compute qty_in_base: receivedQty × (poLine.orderedQtyInBase / poLine.orderedQty).
        // The PO line already snapshotted the factor at order-placement, so the ratio is authoritative.
        //
        // K4 (Kilimanjaro 2026-08-08): this used to re-derive the factor by dividing at 10 dp and then
        // re-round the product to 6 dp — TWO rounding steps, and the second one rounds AWAY from the
        // ordered quantity. On a non-integral factor a receipt of the whole remainder could land a few
        // ULPs above it, which is not an over-receipt but read as one (or, with a tolerance configured,
        // slipped through to fail as a raw DB constraint violation instead of a friendly message).
        // Evaluating it as (receivedQty × orderedQtyInBase) ÷ orderedQty gives exactly ONE rounding step.
        BigDecimal qtyInBase = lineReq.receivedQty()
                .multiply(poLine.getOrderedQtyInBase())
                .divide(poLine.getOrderedQty(), BASE_QTY_SCALE, java.math.RoundingMode.HALF_UP);

        // Over-receipt check (BR-PURCH-10, ADR-0011 D-3): friendly 409 before the DB CHECK fires.
        // The user-facing message stays free of internal detail (ULID, base-unit values, rule/ADR
        // codes) — those go to the log only, so the error shown to the user is safe and readable.
        BigDecimal outstanding = poLine.getOrderedQtyInBase().subtract(poLine.getReceivedQtyInBase());

        // K4: snap the final ULP. Receiving the whole remainder of a line bought in a pack unit whose
        // factor does not divide evenly can round to one ULP above the remainder; snapping means the
        // last receipt closes the line exactly (PO reaches RECEIVED) instead of being rejected — or
        // leaving 0.000001 outstanding forever if we had rounded the other way.
        if (outstanding.signum() > 0
                && qtyInBase.compareTo(outstanding) > 0
                && qtyInBase.subtract(outstanding).compareTo(BASE_QTY_ULP) <= 0) {
            qtyInBase = outstanding;
        }

        // K4: a quantity that converts to zero base units would hit the raw
        // chk_goods_receipt_line_qty_in_base (> 0) DB constraint and surface as an opaque 500.
        // Reject it here with a message the storekeeper can act on.
        if (qtyInBase.signum() <= 0) {
            log.warn("Goods receipt rejected on PO line id={} uid={}: receivedQty={} converts to "
                            + "qtyInBase={} (orderedQty={}, orderedQtyInBase={})",
                    poLine.getId(), poLine.getUid(), lineReq.receivedQty(), qtyInBase,
                    poLine.getOrderedQty(), poLine.getOrderedQtyInBase());
            throw new IllegalArgumentException(
                    "The quantity received for " + poLine.getProductName()
                            + " is too small to record in the product's stock unit. "
                            + "Enter a larger quantity and try again.");
        }
        // Over-receipt is an opt-in per-company policy (Saidi #4): commodities delivered by weight
        // (rice, produce) rarely match the PO exactly. When purchase_settings.receipt_tolerance_pct is
        // set, allow the cumulative received to exceed the ordered amount by up to that percent — the
        // service is authoritative here (the DB CHECK is only a >= 0 sanity guard since V92).
        BigDecimal ceiling = outstanding;
        BigDecimal tolerancePct = purchaseSettings.findByCompanyId(po.getCompanyId())
                .map(PurchaseSettings::getReceiptTolerancePct).orElse(null);
        if (tolerancePct != null && tolerancePct.signum() > 0) {
            // ceiling = ordered × (1 + tol%) − alreadyReceived  (headroom against the ordered total)
            BigDecimal allowedTotal = poLine.getOrderedQtyInBase()
                    .multiply(BigDecimal.ONE.add(tolerancePct.movePointLeft(2)));
            ceiling = allowedTotal.subtract(poLine.getReceivedQtyInBase())
                    .setScale(6, java.math.RoundingMode.HALF_UP);
        }
        if (qtyInBase.compareTo(ceiling) > 0) {
            log.warn("Over-receipt rejected (BR-PURCH-10, ADR-0011 D-3) on PO line id={} uid={}: "
                            + "outstanding={}, tolerancePct={}, ceiling={}, requestedQtyInBase={}",
                    poLine.getId(), poLine.getUid(), outstanding, tolerancePct, ceiling, qtyInBase);
            throw new IllegalStateException(
                    "Over-receipt rejected for " + poLine.getProductName()
                            + ": the quantity received exceeds the outstanding amount on this line. "
                            + "Reduce it and try again.");
        }

        short nextLineNo = (short) (grLines.findMaxLineNo(gr.getId()) + 1);

        GoodsReceiptLine line = new GoodsReceiptLine(
                gr, poLine.getId(), nextLineNo,
                poLine.getProductId(), poLine.getProductCode(), poLine.getProductName(),
                poLine.getUnitId(), poLine.getUnitName(),
                lineReq.receivedQty(), qtyInBase,
                poLine.getUnitCostAmount(), CurrencyCode.value(po.getCurrency()), actorId());

        // V76: capture lot/expiry — all optional; soft validation
        if (lineReq.lotNumber() != null && !lineReq.lotNumber().isBlank()) {
            line.setLotNumber(lineReq.lotNumber().trim());
        }
        line.setManufactureDate(lineReq.manufactureDate());
        line.setExpiryDate(lineReq.expiryDate());

        return line;
    }

    /**
     * Persist serial child rows for a saved GR line (V76).
     * Trims, skips blanks, deduplicates while preserving insertion order.
     * All soft — if serialNumbers is null/empty, this is a no-op.
     */
    private void persistSerials(GoodsReceiptLine line, GoodsReceiptLineRequest req) {
        if (req.serialNumbers() == null || req.serialNumbers().isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : req.serialNumbers()) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;
            seen.add(s);
        }
        for (String serial : seen) {
            grLineSerials.save(new GoodsReceiptLineSerial(
                    line.getId(), line.getCompanyId(), serial, actorId()));
        }
    }

    private List<StockReceivedPayload.LineItem> buildPayloadLines(List<GoodsReceiptLine> savedLines) {
        return savedLines.stream()
                .map(l -> {
                    List<String> serials = grLineSerials.findByGoodsReceiptLineId(l.getId())
                            .stream().map(s -> s.getSerialNumber()).toList();
                    return new StockReceivedPayload.LineItem(
                            l.getProductId(),
                            resolveProductUid(l.getProductId()),
                            l.getUnitId(),
                            l.getQtyInBase(),
                            baseUnitCost(l),         // ADR-0020 D-3 (K4: per BASE unit, not per order unit)
                            l.getLotNumber(),
                            l.getManufactureDate(),
                            l.getExpiryDate(),
                            serials);
                })
                .toList();
    }

    /**
     * Cost of ONE BASE unit for a received line — {@code line_cost_amount ÷ qty_in_base}.
     *
     * <p><b>K4 (Kilimanjaro 2026-08-08) — inventory valuation fix.</b> The payload's
     * {@code unitCostAmount} is consumed by {@code GoodsReceiptStockHandler}, which pairs it with
     * {@code qtyInBase} and multiplies the two ({@code InventoryValuationService.recomputeOnReceipt}
     * → {@code receiptValue = qtyInBase × cost}). {@code goods_receipt_lines.unit_cost_amount} is a
     * cost per ORDER unit, so sending it raw is only correct while the purchase unit happens to equal
     * the base unit. Buy a 12-piece carton at 1,200 against a piece base and the old code valued the
     * receipt at 24 × 1,200 = 28,800 instead of 24 × 100 = 2,400 — inventory and GL 1300 inflated by
     * the pack factor on every single receipt, and the moving average with them.
     *
     * <p>Dividing the LINE TOTAL by the base quantity is factor-agnostic: it is right for a base-unit
     * purchase (where it reduces to the unit cost unchanged) and right for any pack unit, without
     * this service having to know the factor. Rounded to the ledger's own scale
     * ({@code stock_movements.unit_cost_amount} is {@code numeric(19,4)}), so the value stored on the
     * movement — which the void path reverses at — is exactly the value we computed here.
     *
     * <p>Returns ZERO for a defensive non-positive base quantity; {@link #buildGrLine} already
     * rejects that case, so this is unreachable in practice and never divides by zero.
     */
    private BigDecimal baseUnitCost(GoodsReceiptLine line) {
        BigDecimal qtyInBase = line.getQtyInBase();
        BigDecimal lineCost  = line.getLineCostAmount();
        if (qtyInBase == null || qtyInBase.signum() <= 0 || lineCost == null) {
            return BigDecimal.ZERO;
        }
        return lineCost.divide(qtyInBase, MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** Resolve product uid from id — needed to populate the outbox payload (ADR-0011 D-8). */
    private String resolveProductUid(Long productId) {
        return products.findById(productId)
                .map(Product::getUid)
                .orElse("");
    }

    /**
     * Resolve whether the product is lot-tracked — needed so
     * {@link com.erp.modules.stock.events.GoodsReceiptReversalStockHandler} can target the
     * {@code "UNTRACKED"} sentinel batch symmetrically with the forward receipt path (D-2 FIX B).
     */
    private boolean resolveLotTracked(Long companyId, Long productId) {
        return products.findByCompanyIdAndId(companyId, productId)
                .map(Product::isLotTracked)
                .orElse(false);
    }

    private GoodsReceipt requireReceipt(String uid) {
        return Lookups.orNotFound(receipts.findByUid(uid), "GoodsReceipt", uid);
    }

    private PurchaseOrder requireOrderByUid(String uid) {
        return Lookups.orNotFound(orders.findByUid(uid), "PurchaseOrder", uid);
    }

    private PurchaseOrder requireOrderById(Long id) {
        return orders.findById(id)
                .orElseThrow(() -> new NotFoundException("PurchaseOrder not found by id: " + id));
    }

    private GoodsReceiptDto toDto(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
        List<GoodsReceiptLineDto> lineDtos = lines.stream()
                .map(l -> {
                    List<String> serials = grLineSerials.findByGoodsReceiptLineId(l.getId())
                            .stream().map(s -> s.getSerialNumber()).toList();
                    return GoodsReceiptLineDto.from(l, serials);
                })
                .toList();
        PurchaseOrder po = orders.findById(gr.getPurchaseOrderId()).orElse(null);
        return GoodsReceiptDto.from(
                gr,
                po != null ? po.getUid() : null,
                po != null ? po.getSupplierName() : null,
                resolveReceiptCurrency(po, lines),
                receiptTotal(lines),
                lineDtos);
    }

    /**
     * K2: the receipt-level total = Σ line_cost_amount. There is no source column for it, and the
     * documents module is forbidden from deriving amounts (BR-DOC-02 / BR-DOC-09) — so the value
     * printed on the GRN is computed here, once, and carried on the DTO.
     */
    private BigDecimal receiptTotal(List<GoodsReceiptLine> lines) {
        return lines.stream()
                .map(GoodsReceiptLine::getLineCostAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    /** Document currency: the PO header is authoritative; fall back to the first line's snapshot. */
    private String resolveReceiptCurrency(PurchaseOrder po, List<GoodsReceiptLine> lines) {
        if (po != null && po.getCurrency() != null) {
            return CurrencyCode.value(po.getCurrency());
        }
        return lines.isEmpty() ? null : CurrencyCode.value(lines.get(0).getCurrency());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
