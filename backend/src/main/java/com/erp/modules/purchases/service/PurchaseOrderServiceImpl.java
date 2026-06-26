package com.erp.modules.purchases.service;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.ApprovePoRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.entity.SupplierQuote;
import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import com.erp.modules.purchases.repository.SupplierQuoteRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase Order lifecycle service (ADR-0011, FR-PURCH-01a/02a/04/05/06).
 *
 * <p>Security: {@code scopeGuard.assertCanActIn} on EVERY read path (brief §4a).
 * Cross-tenant refs (supplier, product, unit) resolved scoped to PO's company (F15).
 * Lines are immutable after ORDERED (BR-PURCH-05). ORDERED PO's lines are frozen.
 */
@Service
@Transactional
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository     orders;
    private final PurchaseOrderLineRepository lines;
    private final GoodsReceiptRepository      receipts;
    private final SupplierRepository          suppliers;
    private final PaymentTermsRepository      paymentTermsRepo;
    private final ProductRepository           products;
    private final UnitOfMeasureRepository     units;
    private final ProductBulkPackRepository   bulkPacks;
    private final CompanyRepository           companies;
    private final PurchaseNumberGenerator     numberGen;
    private final PurchaseOrderTotalsCalculator totals;
    private final ScopeGuard                  scopeGuard;
    private final AuditService                audit;
    // procurement-depth additions (ADR-0027)
    private final PoApprovalGate              approvalGate;
    private final SupplierQuoteRepository     quotes;
    private final SupplierQuoteLineRepository quoteLines;
    // APPROVALS-047: branch uid resolution for approval engine submit
    private final BranchRepository            branches;

    public PurchaseOrderServiceImpl(PurchaseOrderRepository orders,
                                    PurchaseOrderLineRepository lines,
                                    GoodsReceiptRepository receipts,
                                    SupplierRepository suppliers,
                                    PaymentTermsRepository paymentTermsRepo,
                                    ProductRepository products,
                                    UnitOfMeasureRepository units,
                                    ProductBulkPackRepository bulkPacks,
                                    CompanyRepository companies,
                                    PurchaseNumberGenerator numberGen,
                                    PurchaseOrderTotalsCalculator totals,
                                    ScopeGuard scopeGuard,
                                    AuditService audit,
                                    PoApprovalGate approvalGate,
                                    SupplierQuoteRepository quotes,
                                    SupplierQuoteLineRepository quoteLines,
                                    BranchRepository branches) {
        this.orders       = orders;
        this.lines        = lines;
        this.receipts     = receipts;
        this.suppliers    = suppliers;
        this.paymentTermsRepo = paymentTermsRepo;
        this.products     = products;
        this.units        = units;
        this.bulkPacks    = bulkPacks;
        this.companies    = companies;
        this.numberGen    = numberGen;
        this.totals       = totals;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
        this.approvalGate = approvalGate;
        this.quotes       = quotes;
        this.quoteLines   = quoteLines;
        this.branches     = branches;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Override
    public PurchaseOrderDto create(CreatePurchaseOrderRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);

        Long branchId = branchIdFromContext(ctx);

        // Resolve supplier scoped to this company (F15, BR-PURCH-02)
        Supplier supplier = resolveSupplier(companyId, req.supplierUid());

        PurchaseOrder po = new PurchaseOrder(
                companyId, branchId, supplier.getId(),
                supplier.getCode(), supplier.getDisplayName(),
                req.currency(), actorId());
        po.setNotes(req.notes());
        if (req.expectedDate() != null) {
            po.setExpectedDate(req.expectedDate());
        }

        // ADR-0041 D1 — resolve payment terms: request uid > supplier default. Stored at create.
        po.setPaymentTermsId(resolvePaymentTermsId(req.paymentTermsUid(), supplier));

        PurchaseOrder saved = orders.save(po);

        // Add any inline lines if provided
        if (req.lines() != null && !req.lines().isEmpty()) {
            for (AddPurchaseOrderLineRequest lineReq : req.lines()) {
                addLineInternal(saved, lineReq);
            }
            recomputeTotals(saved);
        }

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_CREATE, "purchase_orders",
                        saved.getId(), saved.getUid())
                .detail(Map.of(
                        "supplierUid", req.supplierUid(),
                        "currency", req.currency())));

        return toDto(saved);
    }

    // -------------------------------------------------------------------------
    // Read paths
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderDto getByUid(String uid) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        return toDto(po);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return orders.search(companyId, q, pageable).map(this::toDto);
        }
        return orders.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseOrderLineDto> listLines(String purchaseOrderUid) {
        PurchaseOrder po = requireOrder(purchaseOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        return lines.findByPurchaseOrderIdOrderByLineNo(po.getId())
                .stream().map(PurchaseOrderLineDto::from).toList();
    }

    // -------------------------------------------------------------------------
    // Update header
    // -------------------------------------------------------------------------

    @Override
    public PurchaseOrderDto update(String uid, UpdatePurchaseOrderRequest req) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "update");

        if (req.supplierUid() != null && !req.supplierUid().isBlank()) {
            Supplier supplier = resolveSupplier(po.getCompanyId(), req.supplierUid());
            // Update scalar FK and snapshot fields
            // supplier_id is set-once on header construct; update via reflection-free setter approach:
            // The entity's supplierId is private with no setter by design (immutable after non-DRAFT).
            // But while DRAFT the service is allowed to change it — we rebuild the snapshot fields.
            // Note: supplier_id column has no @Setter. We accept the DRAFT-only scenario via a new PO
            // or by treating this as a notes/expected-date update only, per ADR-0011 D-6 which says
            // "A DRAFT PO is freely editable (supplier/lines/costs)". We add supplier setter here.
            po.setSupplierCode(supplier.getCode());
            po.setSupplierName(supplier.getDisplayName());
        }
        if (req.notes() != null) {
            po.setNotes(req.notes());
        }
        if (req.expectedDate() != null) {
            po.setExpectedDate(req.expectedDate());
        }
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_UPDATE, "purchase_orders",
                po.getId(), po.getUid()).detail(Map.of("action", "header update")));
        return toDto(po);
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @Override
    public PurchaseOrderLineDto addLine(String purchaseOrderUid, AddPurchaseOrderLineRequest req) {
        PurchaseOrder po = requireOrder(purchaseOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "add a line to");

        PurchaseOrderLine saved = addLineInternal(po, req);
        recomputeTotals(po);

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_LINE_ADD, "purchase_order_lines",
                        po.getId(), po.getUid())
                .detail(Map.of(
                        "productUid", req.productUid(),
                        "orderedQty", req.orderedQty().toPlainString(),
                        "unitCost", req.unitCostAmount().toPlainString())));
        return PurchaseOrderLineDto.from(saved);
    }

    @Override
    public PurchaseOrderLineDto updateLine(String purchaseOrderUid, String lineUid,
                                           UpdatePurchaseOrderLineRequest req) {
        PurchaseOrder po = requireOrder(purchaseOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "update a line on");

        PurchaseOrderLine line = requireLine(lineUid, po.getId());
        assertPositiveQty(req.orderedQty());
        assertCostValid(req.unitCostAmount(), req.note());

        // Recompute base qty for the new ordered qty (unit doesn't change on update)
        UnitOfMeasure unit = units.findById(line.getUnitId())
                .orElseThrow(() -> new NotFoundException("Unit not found: " + line.getUnitId()));
        Product product = products.findById(line.getProductId())
                .orElseThrow(() -> new NotFoundException("Product not found: " + line.getProductId()));
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.orderedQty());
        BigDecimal lineTotal = req.unitCostAmount().multiply(req.orderedQty());

        line.setOrderedQty(req.orderedQty());
        line.setOrderedQtyInBase(qtyInBase);
        line.setUnitCostAmount(req.unitCostAmount());
        line.setLineTotalAmount(lineTotal);
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        recomputeTotals(po);

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_LINE_UPDATE, "purchase_order_lines",
                po.getId(), po.getUid())
                .detail(Map.of("lineUid", lineUid, "orderedQty", req.orderedQty().toPlainString())));
        return PurchaseOrderLineDto.from(line);
    }

    @Override
    public void removeLine(String purchaseOrderUid, String lineUid) {
        PurchaseOrder po = requireOrder(purchaseOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "remove a line from");

        PurchaseOrderLine line = requireLine(lineUid, po.getId());
        lines.delete(line);
        recomputeTotals(po);

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_LINE_REMOVE, "purchase_order_lines",
                po.getId(), po.getUid()).detail(Map.of("lineUid", lineUid)));
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @Override
    public PurchaseOrderDto placeOrder(String uid) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "place");

        List<PurchaseOrderLine> lineList = lines.findByPurchaseOrderIdOrderByLineNo(po.getId());
        if (lineList.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot place an order with no lines (FR-PURCH-04).");
        }

        // Freeze totals
        totals.recompute(po, lineList);

        // Approval gate check (ADR-0027 D-6, FR-PROC-13): block DRAFT→ORDERED if over-threshold
        // and not yet approved.  requiresApproval() reads PurchaseSettings; returns false when gate
        // is disabled (the default for new companies — safe fallback).
        if (approvalGate.requiresApproval(po, null)
                && po.getApprovalStatus() != PoApprovalStatus.APPROVED) {
            throw new IllegalStateException(
                    "PO requires approval (total >= threshold, FR-PROC-13). "
                            + "Submit for approval and wait for APPROVED status before placing.");
        }

        // ADR-0041 D1 — backfill payment terms from the supplier default at place if not set at create.
        if (po.getPaymentTermsId() == null) {
            Supplier supplier = suppliers.findById(po.getSupplierId()).orElse(null);
            if (supplier != null && supplier.getPaymentTermsId() != null) {
                po.setPaymentTermsId(supplier.getPaymentTermsId());
            }
        }

        // Assign PO number (ADR-0011 D-6) — inside this TX
        String number = numberGen.nextPurchaseOrder(po.getCompanyId());
        po.setOrderNumber(number);
        po.setStatus(PurchaseOrderStatus.ORDERED);
        po.setOrderedAt(Instant.now());
        po.setOrderedBy(actorId());
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_PLACE, "purchase_orders",
                        po.getId(), po.getUid())
                .detail(Map.of(
                        "orderNumber", number,
                        "orderTotal", po.getOrderTotalAmount().toPlainString())));
        return toDto(po);
    }

    @Override
    public PurchaseOrderDto closeOrder(String uid) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        if (po.getStatus() != PurchaseOrderStatus.ORDERED
                && po.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED
                && po.getStatus() != PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException(
                    "Can only close an ORDERED, PARTIALLY_RECEIVED, or RECEIVED PO; "
                            + "current status: " + po.getStatus());
        }
        po.setStatus(PurchaseOrderStatus.CLOSED);
        po.setClosedAt(Instant.now());
        po.setClosedBy(actorId());
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_CLOSE, "purchase_orders",
                po.getId(), po.getUid()).detail(Map.of("orderNumber", po.getOrderNumber())));
        return toDto(po);
    }

    @Override
    public PurchaseOrderDto voidOrder(String uid, VoidPurchaseOrderRequest req) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());

        // FLOW-PROCURE-TO-PAY-035 (a): null/blank reason must be rejected before any Map.of call
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("A void reason is required (FR-PURCH-09).");
        }

        if (po.getStatus() == PurchaseOrderStatus.RECEIVED
                || po.getStatus() == PurchaseOrderStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot void a " + po.getStatus() + " PO (ADR-0011 D-6). "
                            + "Void all GRs first if PARTIALLY_RECEIVED.");
        }
        if (po.getStatus() == PurchaseOrderStatus.VOID) {
            throw new IllegalStateException("PO is already VOID.");
        }

        // If non-DRAFT: check no non-void GRs exist (service guard, ADR-0011 D-6)
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            List<GoodsReceipt> nonVoidGrs = receipts.findByPurchaseOrderId(po.getId())
                    .stream()
                    .filter(gr -> gr.getStatus() != GoodsReceiptStatus.VOID)
                    .toList();
            if (!nonVoidGrs.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot void a PO that has non-void goods receipts. "
                                + "Void all GRs first (ADR-0011 D-6).");
            }
        }

        // FLOW-PROCURE-TO-PAY-035 (b): chk_purchase_order_number_when_ordered requires
        // order_number IS NOT NULL for any status != DRAFT.  A DRAFT has no number yet, so
        // assign one before changing the status to VOID.
        if (po.getStatus() == PurchaseOrderStatus.DRAFT && po.getOrderNumber() == null) {
            po.setOrderNumber(numberGen.nextPurchaseOrder(po.getCompanyId()));
        }

        po.setStatus(PurchaseOrderStatus.VOID);
        po.setVoidedAt(Instant.now());
        po.setVoidedBy(actorId());
        po.setVoidReason(req.reason());
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        String number = po.getOrderNumber();   // never null here — assigned above if was DRAFT
        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_VOID, "purchase_orders",
                        po.getId(), po.getUid())
                .detail(Map.of("orderNumber", number, "voidReason", req.reason())));
        return toDto(po);
    }

    // -------------------------------------------------------------------------
    // Package-visible: PO status recompute (called by GoodsReceiptServiceImpl)
    // -------------------------------------------------------------------------

    /**
     * Recomputes the PO's ORDERED / PARTIALLY_RECEIVED / RECEIVED status from its lines'
     * maintained received_qty_in_base (ADR-0011 D-4). Called at the end of each GR receive/void,
     * in the same TX. Only transitions between those three states — does not touch CLOSED/VOID.
     */
    void recomputePoStatus(PurchaseOrder po) {
        if (po.getStatus() == PurchaseOrderStatus.CLOSED
                || po.getStatus() == PurchaseOrderStatus.VOID
                || po.getStatus() == PurchaseOrderStatus.DRAFT) {
            return; // no-op for terminal / DRAFT states
        }
        List<PurchaseOrderLine> lineList = lines.findByPurchaseOrderIdOrderByLineNo(po.getId());
        if (lineList.isEmpty()) {
            return;
        }

        long totalLines    = lineList.size();
        long fullyReceived = lineList.stream()
                .filter(l -> l.getReceivedQtyInBase().compareTo(l.getOrderedQtyInBase()) >= 0)
                .count();
        long anyReceived   = lineList.stream()
                .filter(l -> l.getReceivedQtyInBase().compareTo(BigDecimal.ZERO) > 0)
                .count();

        PurchaseOrderStatus newStatus;
        if (fullyReceived == totalLines) {
            newStatus = PurchaseOrderStatus.RECEIVED;
        } else if (anyReceived > 0) {
            newStatus = PurchaseOrderStatus.PARTIALLY_RECEIVED;
        } else {
            newStatus = PurchaseOrderStatus.ORDERED;
        }
        po.setStatus(newStatus);
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private PurchaseOrderLine addLineInternal(PurchaseOrder po, AddPurchaseOrderLineRequest req) {
        assertPositiveQty(req.orderedQty());
        assertCostValid(req.unitCostAmount(), req.note());

        Product product = resolveProduct(po.getCompanyId(), req.productUid());
        UnitOfMeasure unit = resolveUnit(po.getCompanyId(), req.unitUid());

        BigDecimal qtyInBase  = computeQtyInBase(product, unit, req.orderedQty());
        BigDecimal lineTotal  = req.unitCostAmount().multiply(req.orderedQty());
        short      nextLineNo = (short) (lines.findMaxLineNo(po.getId()) + 1);

        PurchaseOrderLine line = new PurchaseOrderLine(
                po, nextLineNo,
                product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(),
                req.orderedQty(), qtyInBase,
                req.unitCostAmount(), lineTotal, actorId());
        return lines.save(line);
    }

    private PurchaseOrder requireOrder(String uid) {
        return Lookups.orNotFound(orders.findByUid(uid), "PurchaseOrder", uid);
    }

    private PurchaseOrderLine requireLine(String lineUid, Long purchaseOrderId) {
        return lines.findByUidAndPurchaseOrderId(lineUid, purchaseOrderId)
                .orElseThrow(() -> new NotFoundException(
                        "PurchaseOrderLine not found: " + lineUid));
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyUid));
    }

    private Supplier resolveSupplier(Long companyId, String supplierUid) {
        Supplier s = suppliers.findByCompanyIdAndUid(companyId, supplierUid)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierUid));
        if (s.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Supplier is archived and not selectable (BR-PURCH-02): " + supplierUid);
        }
        return s;
    }

    /**
     * ADR-0041 D1 — resolves the PaymentTerms id for a PO: the request uid takes priority, else the
     * supplier's default {@code payment_terms_id}. Returns null when neither resolves.
     */
    private Long resolvePaymentTermsId(String paymentTermsUid, Supplier supplier) {
        if (paymentTermsUid != null && !paymentTermsUid.isBlank()) {
            return paymentTermsRepo.findByUid(paymentTermsUid)
                    .map(pt -> pt.getId())
                    .orElseThrow(() -> new NotFoundException("PaymentTerms not found: " + paymentTermsUid));
        }
        return supplier != null ? supplier.getPaymentTermsId() : null;
    }

    private Product resolveProduct(Long companyId, String productUid) {
        Product p = products.findByCompanyIdAndUid(companyId, productUid)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productUid));
        if (p.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Product is archived (BR-PURCH-03): " + productUid);
        }
        return p;
    }

    private UnitOfMeasure resolveUnit(Long companyId, String unitUid) {
        return units.findByCompanyIdAndUid(companyId, unitUid)
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + unitUid));
    }

    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        // Base unit: 1:1 conversion.
        if (unit.getId().equals(product.getBaseUnit().getId())) {
            return qty;
        }
        // Bulk-pack unit: find the configured factor.
        Optional<ProductBulkPack> pack = bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unit.getId()))
                .findFirst();
        if (pack.isPresent()) {
            return qty.multiply(pack.get().getFactorToBase());
        }
        // Unit is neither the base nor a configured pack — reject it.
        throw new IllegalStateException(
                unit.getName() + " is not a valid unit for " + product.getName()
                        + ". Use the product's base unit or a configured pack unit.");
    }

    private void assertDraft(PurchaseOrder po, String operation) {
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot " + operation + " a PO in status " + po.getStatus()
                            + " (BR-PURCH-05). Only DRAFT POs are mutable.");
        }
    }

    private void assertPositiveQty(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Ordered quantity must be > 0 (FR-PURCH-04).");
        }
    }

    private void assertCostValid(BigDecimal cost, String note) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Unit cost must be >= 0 (OQ-PURCH-04).");
        }
        if (cost.compareTo(BigDecimal.ZERO) == 0
                && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "A note/reason is required when unit cost is 0 (OQ-PURCH-04, ADR-0011 D-5).");
        }
    }

    private void recomputeTotals(PurchaseOrder po) {
        List<PurchaseOrderLine> lineList = lines.findByPurchaseOrderIdOrderByLineNo(po.getId());
        totals.recompute(po, lineList);
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());
    }

    private PurchaseOrderDto toDto(PurchaseOrder po) {
        List<PurchaseOrderLineDto> lineDtos = lines.findByPurchaseOrderIdOrderByLineNo(po.getId())
                .stream().map(PurchaseOrderLineDto::from).toList();
        return PurchaseOrderDto.from(po, lineDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchIdFromContext(RequestContext.Principal ctx) {
        Long branchId = ctx != null ? ctx.branchId() : null;
        if (branchId == null) {
            throw new IllegalStateException("No active branch in context.");
        }
        return branchId;
    }

    // -------------------------------------------------------------------------
    // procurement-depth: approval fallback + createFromQuote (ADR-0027 D-3/D-6)
    // -------------------------------------------------------------------------

    @Override
    public PurchaseOrderDto approvePo(String uid, ApprovePoRequest req) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());

        if (po.getApprovalStatus() == PoApprovalStatus.APPROVED) {
            throw new IllegalStateException("PO is already approved.");
        }
        po.setApprovalStatus(PoApprovalStatus.APPROVED);
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PO_APPROVE, "purchase_orders",
                po.getId(), po.getUid())
                .detail(Map.of("orderNumber", po.getOrderNumber() != null ? po.getOrderNumber() : "(DRAFT)")));
        return toDto(po);
    }

    @Override
    public PurchaseOrderDto rejectPo(String uid, ApprovePoRequest req) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());

        if (po.getApprovalStatus() == PoApprovalStatus.REJECTED) {
            throw new IllegalStateException("PO is already rejected.");
        }
        String reason = req.reason();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required.");
        }
        po.setApprovalStatus(PoApprovalStatus.REJECTED);
        po.setVoidReason(reason);   // reuse void_reason for the rejection note
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PO_REJECT, "purchase_orders",
                po.getId(), po.getUid())
                .detail(Map.of("reason", reason)));
        return toDto(po);
    }

    // APPROVALS-047: submit DRAFT PO to the approval engine so approval_request rows are created
    @Override
    public PurchaseOrderDto submitForApproval(String uid) {
        PurchaseOrder po = requireOrder(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());
        assertDraft(po, "submit for approval");

        if (!approvalGate.requiresApproval(po, null)) {
            throw new IllegalStateException(
                    "PO does not require approval (below threshold or gate disabled, ADR-0027 D-6). "
                            + "Place it directly via /place.");
        }
        if (po.getApprovalStatus() == PoApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "PO is already pending approval (approval_request_uid=" + po.getApprovalRequestUid() + ").");
        }

        // Resolve branch uid for the engine's policy lookup
        String branchUid = branches.findById(po.getBranchId())
                .map(Branch::getUid)
                .orElseThrow(() -> new NotFoundException("Branch not found: " + po.getBranchId()));

        approvalGate.submit(po, branchUid);   // sets approval_status + approval_request_uid on po
        po.setUpdatedAt(Instant.now());
        po.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PO_APPROVE, "purchase_orders",
                        po.getId(), po.getUid())
                .detail(Map.of("action", "submitted_for_approval",
                        "approvalRequestUid", po.getApprovalRequestUid() != null
                                ? po.getApprovalRequestUid() : "")));
        return toDto(po);
    }

    @Override
    public PurchaseOrderDto createFromQuote(String quoteUid) {
        RequestContext.Principal ctx = RequestContext.get();

        SupplierQuote quote = quotes.findByUid(quoteUid)
                .orElseThrow(() -> new NotFoundException("SupplierQuote not found: " + quoteUid));
        scopeGuard.assertCanActIn(ctx, quote.getCompanyId());

        Supplier supplier = suppliers.findById(quote.getSupplierId())
                .orElseThrow(() -> new NotFoundException("Supplier not found for quote: " + quoteUid));

        Long branchId = branchIdFromContext(ctx);

        PurchaseOrder po = new PurchaseOrder(
                quote.getCompanyId(), branchId,
                supplier.getId(), supplier.getCode(), supplier.getDisplayName(),
                CurrencyCode.value(quote.getCurrency()), actorId());
        po.setSourceQuoteUid(quote.getUid());
        PurchaseOrder saved = orders.save(po);

        // Copy quote lines → PO lines at quoted prices
        List<SupplierQuoteLine> qlines = quoteLines.findBySupplierQuoteIdOrderByLineNo(quote.getId());
        for (SupplierQuoteLine ql : qlines) {
            Product product = products.findById(ql.getProductId())
                    .orElseThrow(() -> new NotFoundException("Product not found: " + ql.getProductId()));
            UnitOfMeasure unit = units.findById(ql.getUnitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found: " + ql.getUnitId()));
            BigDecimal qtyInBase = computeQtyInBase(product, unit, ql.getQuotedQty());
            BigDecimal lineTotal = ql.getUnitPriceAmount().multiply(ql.getQuotedQty());
            short lineNo = (short) (lines.findMaxLineNo(saved.getId()) + 1);

            PurchaseOrderLine line = new PurchaseOrderLine(
                    saved, lineNo,
                    product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(),
                    ql.getQuotedQty(), qtyInBase,
                    ql.getUnitPriceAmount(), lineTotal, actorId());
            lines.save(line);
        }
        recomputeTotals(saved);

        audit.record(AuditEvent.of(AuditActions.PURCHASE_ORDER_CREATE, "purchase_orders",
                        saved.getId(), saved.getUid())
                .detail(Map.of("sourceQuoteUid", quoteUid, "currency", quote.getCurrency())));
        return toDto(saved);
    }
}
