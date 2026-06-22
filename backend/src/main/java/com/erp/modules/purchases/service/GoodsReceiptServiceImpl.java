package com.erp.modules.purchases.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.StockReceivedPayload;
import com.erp.modules.purchases.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
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
import java.util.List;
import java.util.Map;
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

    private final GoodsReceiptRepository      receipts;
    private final GoodsReceiptLineRepository  grLines;
    private final PurchaseOrderRepository     orders;
    private final PurchaseOrderLineRepository poLines;
    private final ProductRepository           products;
    private final PurchaseNumberGenerator     numberGen;
    private final OutstandingTracker          tracker;
    private final PurchaseOrderServiceImpl    poService;  // for recomputePoStatus
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final OutboxPublisher            outbox;

    public GoodsReceiptServiceImpl(GoodsReceiptRepository receipts,
                                   GoodsReceiptLineRepository grLines,
                                   PurchaseOrderRepository orders,
                                   PurchaseOrderLineRepository poLines,
                                   ProductRepository products,
                                   PurchaseNumberGenerator numberGen,
                                   OutstandingTracker tracker,
                                   PurchaseOrderServiceImpl poService,
                                   ScopeGuard scopeGuard,
                                   AuditService audit,
                                   OutboxPublisher outbox) {
        this.receipts   = receipts;
        this.grLines    = grLines;
        this.orders     = orders;
        this.poLines    = poLines;
        this.products   = products;
        this.numberGen  = numberGen;
        this.tracker    = tracker;
        this.poService  = poService;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
        this.outbox     = outbox;
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
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalArgumentException(
                    "A Goods Receipt must have at least one line (FR-PURCH-07).");
        }
        for (GoodsReceiptLineRequest lineReq : req.lines()) {
            GoodsReceiptLine grLine = buildGrLine(saved, po, lineReq);
            savedLines.add(grLines.save(grLine));
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
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("A void reason is required (FR-PURCH-09).");
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
        List<StockReceiptVoidedPayload.LineItem> payloadLines = lineList.stream()
                .map(l -> new StockReceiptVoidedPayload.LineItem(
                        l.getProductId(),
                        resolveProductUid(l.getProductId()),
                        l.getUnitId(),
                        l.getQtyInBase()))
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
                        "PurchaseOrderLine not found or does not belong to PO "
                                + po.getUid() + ": " + lineReq.purchaseOrderLineUid()));

        // Validate received qty > 0
        if (lineReq.receivedQty() == null || lineReq.receivedQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Received quantity must be > 0 on line " + lineReq.purchaseOrderLineUid());
        }

        // Compute qty_in_base: receivedQty × (poLine.orderedQtyInBase / poLine.orderedQty)
        // This is safe: the PO line already snapshotted the factor at order-placement.
        BigDecimal factor = poLine.getOrderedQtyInBase().divide(
                poLine.getOrderedQty(), 10, java.math.RoundingMode.HALF_UP);
        BigDecimal qtyInBase = lineReq.receivedQty().multiply(factor)
                .setScale(6, java.math.RoundingMode.HALF_UP);

        // Over-receipt check (BR-PURCH-10, ADR-0011 D-3): friendly 409 before the DB CHECK fires.
        // The user-facing message stays free of internal detail (ULID, base-unit values, rule/ADR
        // codes) — those go to the log only, so the error shown to the user is safe and readable.
        BigDecimal outstanding = poLine.getOrderedQtyInBase().subtract(poLine.getReceivedQtyInBase());
        if (qtyInBase.compareTo(outstanding) > 0) {
            log.warn("Over-receipt rejected (BR-PURCH-10, ADR-0011 D-3) on PO line id={} uid={}: "
                            + "outstanding={}, requestedQtyInBase={}",
                    poLine.getId(), poLine.getUid(), outstanding, qtyInBase);
            throw new IllegalStateException(
                    "Over-receipt rejected for " + poLine.getProductName()
                            + ": the quantity received exceeds the outstanding amount on this line. "
                            + "Reduce it and try again.");
        }

        short nextLineNo = (short) (grLines.findMaxLineNo(gr.getId()) + 1);

        return new GoodsReceiptLine(
                gr, poLine.getId(), nextLineNo,
                poLine.getProductId(), poLine.getProductCode(), poLine.getProductName(),
                poLine.getUnitId(), poLine.getUnitName(),
                lineReq.receivedQty(), qtyInBase,
                poLine.getUnitCostAmount(), CurrencyCode.value(po.getCurrency()), actorId());
    }

    private List<StockReceivedPayload.LineItem> buildPayloadLines(List<GoodsReceiptLine> grLines) {
        return grLines.stream()
                .map(l -> new StockReceivedPayload.LineItem(
                        l.getProductId(),
                        resolveProductUid(l.getProductId()),
                        l.getUnitId(),
                        l.getQtyInBase(),
                        l.getUnitCostAmount()))  // ADR-0020 D-3: carry cost into the stock event
                .toList();
    }

    /** Resolve product uid from id — needed to populate the outbox payload (ADR-0011 D-8). */
    private String resolveProductUid(Long productId) {
        return products.findById(productId)
                .map(Product::getUid)
                .orElse("");
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
                .map(GoodsReceiptLineDto::from).toList();
        String poUid = orders.findById(gr.getPurchaseOrderId())
                .map(PurchaseOrder::getUid)
                .orElse(null);
        return GoodsReceiptDto.from(gr, poUid, lineDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
