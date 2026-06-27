package com.erp.modules.manufacturing.service;

import com.erp.modules.manufacturing.domain.dto.ApplyCostRequest;
import com.erp.modules.manufacturing.domain.dto.CloseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CompleteWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderCostReportDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderComponentDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderOperationDto;
import com.erp.modules.manufacturing.domain.entity.WorkOrder;
import com.erp.modules.manufacturing.domain.entity.WorkOrderComponent;
import com.erp.modules.manufacturing.domain.entity.WorkOrderOperation;
import com.erp.modules.manufacturing.domain.enums.ComponentLineStatus;
import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import com.erp.modules.manufacturing.repository.WorkOrderComponentRepository;
import com.erp.modules.manufacturing.repository.WorkOrderOperationRepository;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.modules.manufacturing.service.ManufacturingGlPoster.ComponentCostLeg;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.service.BomExplosionService;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.LocationResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;

import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Costing transitions for work orders (ADR-0035 D-5):
 * issue components → apply labour/overhead → complete (FG receipt) → close (variance clear).
 *
 * <p>Every method is synchronous: stock posting + GL posting happen inside the same TX
 * (human-act posture per ADR-0035). WORK_ORDER.* outbox events are informational only.
 */
@Service
@Transactional
public class WorkOrderCostingServiceImpl implements WorkOrderCostingService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCostingServiceImpl.class);
    private static final int    SCALE = 4;

    private final WorkOrderRepository          workOrders;
    private final WorkOrderComponentRepository components;
    private final WorkOrderOperationRepository operations;
    private final ProductRepository            products;
    private final BomExplosionService          bomExplosion;
    private final StockPostingService          stockPosting;
    private final InventoryValuationService    valuation;
    private final LocationResolver             locationResolver;
    private final ManufacturingGlPoster        glPoster;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;
    private final OutboxPublisher              outbox;

    public WorkOrderCostingServiceImpl(WorkOrderRepository workOrders,
                                        WorkOrderComponentRepository components,
                                        WorkOrderOperationRepository operations,
                                        ProductRepository products,
                                        BomExplosionService bomExplosion,
                                        StockPostingService stockPosting,
                                        InventoryValuationService valuation,
                                        LocationResolver locationResolver,
                                        ManufacturingGlPoster glPoster,
                                        ScopeGuard scopeGuard,
                                        AuditService audit,
                                        OutboxPublisher outbox) {
        this.workOrders      = workOrders;
        this.components      = components;
        this.operations      = operations;
        this.products        = products;
        this.bomExplosion    = bomExplosion;
        this.stockPosting    = stockPosting;
        this.valuation       = valuation;
        this.locationResolver = locationResolver;
        this.glPoster        = glPoster;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
        this.outbox          = outbox;
    }

    // -------------------------------------------------------------------------
    // Issue components (RELEASED → IN_PROGRESS on first issue)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto issueComponents(String workOrderUid, IssueComponentsRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        WorkOrderStatus st = wo.getStatus();
        if (st != WorkOrderStatus.RELEASED && st != WorkOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Components can only be issued for RELEASED or IN_PROGRESS work orders.");
        }

        // Ensure component lines exist (materialise BOM explosion if not yet done)
        List<WorkOrderComponent> compLines = components.findByWorkOrderIdOrderByLineNoAsc(wo.getId());
        if (compLines.isEmpty()) {
            compLines = materialiseComponents(wo, principal.userId());
        }

        // Resolve target component lines
        List<WorkOrderComponent> targets;
        if (req.full()) {
            targets = compLines.stream()
                    .filter(c -> c.getStatus() != ComponentLineStatus.ISSUED)
                    .toList();
        } else if (req.componentUids() != null && !req.componentUids().isEmpty()) {
            targets = compLines.stream()
                    .filter(c -> req.componentUids().contains(c.getUid()))
                    .toList();
        } else {
            // default: all unissued lines
            targets = compLines.stream()
                    .filter(c -> c.getStatus() != ComponentLineStatus.ISSUED)
                    .toList();
        }

        if (targets.isEmpty()) {
            throw new IllegalStateException("No unissued component lines to process.");
        }

        Long locationId = locationResolver.defaultLocationId(wo.getCompanyId(), wo.getBranchId());
        List<ComponentCostLeg> costLegs = new ArrayList<>();

        for (WorkOrderComponent comp : targets) {
            BigDecimal qtyToIssue = comp.getPlannedQty().subtract(comp.getIssuedQty());
            if (qtyToIssue.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Stock posting: PRODUCTION_ISSUE (negative qty from on-hand)
            BigDecimal issuedValue = valuation.costIssue(
                    wo.getCompanyId(), wo.getBranchId(), comp.getComponentProductId(), qtyToIssue);

            BigDecimal unitCost = null;
            if (issuedValue != null) {
                unitCost = issuedValue.divide(qtyToIssue, SCALE, RoundingMode.HALF_UP);
                stockPosting.post(
                        wo.getCompanyId(), wo.getBranchId(), locationId,
                        comp.getComponentProductId(),
                        qtyToIssue.negate(),
                        MovementType.PRODUCTION_ISSUE,
                        null, "WORK_ORDER", wo.getUid(),
                        null, "WO " + wo.getWoNumber() + " component issue",
                        Instant.now(), principal.userId(),
                        unitCost, issuedValue.negate());

                comp.recordIssue(qtyToIssue, issuedValue, unitCost, principal.userId());
                wo.accumulateWipDebit(issuedValue, principal.userId());
                costLegs.add(new ComponentCostLeg(comp.getComponentProductCode(), issuedValue));
            } else {
                // avg_cost is NULL — cost-skip (BR-MFG-06)
                stockPosting.post(
                        wo.getCompanyId(), wo.getBranchId(), locationId,
                        comp.getComponentProductId(),
                        qtyToIssue.negate(),
                        MovementType.PRODUCTION_ISSUE,
                        null, "WORK_ORDER", wo.getUid(),
                        null, "WO " + wo.getWoNumber() + " component issue (no cost)",
                        Instant.now(), principal.userId(),
                        null, null);
                comp.markCostSkipped(qtyToIssue, principal.userId());
                wo.flagIncompleteCost();
                log.warn("WorkOrderCostingService: avg_cost NULL for product {} on WO {} — cost skipped (BR-MFG-06).",
                        comp.getComponentProductCode(), wo.getWoNumber());
            }
        }

        // GL: DR WIP / CR Inventory (only for costed legs)
        if (!costLegs.isEmpty()) {
            glPoster.postComponentIssue(wo, costLegs, req.postingDate(), principal.userId());
        }

        // Transition to IN_PROGRESS on first issue
        if (st == WorkOrderStatus.RELEASED) {
            wo.startInProgress(principal.userId());
        }

        audit.record(AuditEvent.of(AuditActions.WORKORDER_ISSUE_COMPONENTS, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("linesIssued", targets.size())));
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Apply labour/overhead
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto applyCost(String workOrderUid, ApplyCostRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        WorkOrderStatus st = wo.getStatus();
        if (st != WorkOrderStatus.IN_PROGRESS && st != WorkOrderStatus.RELEASED) {
            throw new IllegalStateException("Can only apply cost to RELEASED or IN_PROGRESS work orders.");
        }

        BigDecimal labour   = req.labourAmount()   != null ? req.labourAmount()   : BigDecimal.ZERO;
        BigDecimal overhead = req.overheadAmount()  != null ? req.overheadAmount() : BigDecimal.ZERO;

        if (labour.compareTo(BigDecimal.ZERO) == 0 && overhead.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("At least one of labourAmount or overheadAmount must be non-zero.");
        }

        // If tied to an operation, mark it applied (idempotency guard)
        if (req.operationUid() != null) {
            WorkOrderOperation op = operations.findByWorkOrderIdAndUid(wo.getId(), req.operationUid())
                    .orElseThrow(() -> new NotFoundException("Operation not found."));
            if (op.isApplied()) {
                throw new IllegalStateException("This operation has already been applied.");
            }
            op.markApplied(principal.userId());
        }

        wo.accumulateLabourOverhead(labour, overhead, principal.userId());
        glPoster.postLabourOverhead(wo, labour, overhead, req.postingDate(), principal.userId());

        audit.record(AuditEvent.of(AuditActions.WORKORDER_APPLY_COST, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("labour", labour, "overhead", overhead)));
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Complete (FG receipt — IN_PROGRESS → COMPLETED)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto complete(String workOrderUid, CompleteWorkOrderRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        if (wo.getStatus() != WorkOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only IN_PROGRESS work orders can be completed.");
        }

        BigDecimal goodQty  = req.goodQty();
        BigDecimal scrapQty = req.scrapQty() != null ? req.scrapQty() : BigDecimal.ZERO;
        BigDecimal totalOut = goodQty.add(scrapQty);

        if (!Boolean.TRUE.equals(req.allowOverRun()) && totalOut.compareTo(wo.getPlannedQty()) > 0) {
            throw new IllegalArgumentException(
                    "Output (" + totalOut + ") exceeds planned qty (" + wo.getPlannedQty()
                    + "). Set allowOverRun=true to override (BR-MFG-07).");
        }

        // Compute WIP cost allocated to this completion: wipDebit − wipCredit so far = open WIP
        BigDecimal openWip = wo.openWip();
        BigDecimal relievedValue;
        BigDecimal unitCost;

        if (goodQty.compareTo(BigDecimal.ZERO) > 0 && openWip.compareTo(BigDecimal.ZERO) > 0) {
            // If this completion fills the full planned order qty, relieve ALL remaining open WIP
            // exactly (no divide-then-multiply rounding loss) — Finding 4 fix (ADR-0035 OQ-MFG-02).
            BigDecimal totalGoodAfterThis = wo.getGoodQty().add(goodQty);
            boolean isFinalCompletion = totalGoodAfterThis.compareTo(wo.getPlannedQty()) >= 0;
            if (isFinalCompletion) {
                relievedValue = openWip; // exact — no rounding residual at final completion
                unitCost = openWip.divide(goodQty, SCALE, RoundingMode.HALF_UP);
            } else {
                unitCost = openWip.divide(goodQty, SCALE, RoundingMode.HALF_UP);
                // cap to openWip to prevent over-relief
                relievedValue = unitCost.multiply(goodQty).setScale(SCALE, RoundingMode.HALF_UP)
                                        .min(openWip);
            }
        } else {
            if (openWip.compareTo(BigDecimal.ZERO) <= 0) {
                // openWip is zero or negative — clamp to 0 per ADR-0035 D-5; log WARN (Finding 3 fix)
                log.warn("WorkOrderCostingServiceImpl.complete: openWip={} for WO {} — relievedValue clamped to 0; "
                        + "posting zero-value GL entry to maintain WIP recon integrity.",
                        openWip, wo.getWoNumber());
            }
            unitCost      = BigDecimal.ZERO;
            relievedValue = BigDecimal.ZERO;
        }

        // Receive finished goods into stock at computed cost
        Long locationId = locationResolver.defaultLocationId(wo.getCompanyId(), wo.getBranchId());
        if (goodQty.compareTo(BigDecimal.ZERO) > 0) {
            if (relievedValue.compareTo(BigDecimal.ZERO) > 0) {
                valuation.recomputeOnReceipt(
                        wo.getCompanyId(), wo.getBranchId(), wo.getFinishedProductId(),
                        goodQty, unitCost);
            }
            stockPosting.post(
                    wo.getCompanyId(), wo.getBranchId(), locationId,
                    wo.getFinishedProductId(),
                    goodQty,
                    MovementType.PRODUCTION_RECEIPT,
                    null, "WORK_ORDER", wo.getUid(),
                    null, "WO " + wo.getWoNumber() + " FG receipt",
                    Instant.now(), principal.userId(),
                    unitCost.compareTo(BigDecimal.ZERO) > 0 ? unitCost : null,
                    relievedValue.compareTo(BigDecimal.ZERO) > 0 ? relievedValue : null);

            // GL: DR Finished-Goods / CR WIP — always post to maintain WIP recon integrity (Finding 3 fix)
            glPoster.postFinishedGoodsReceipt(wo, relievedValue, req.postingDate(), principal.userId());
        }

        wo.recordCompletion(goodQty, scrapQty, unitCost, relievedValue, principal.userId());

        // Informational outbox event
        outbox.publish(DomainEventType.WORK_ORDER_COMPLETED, DomainEventType.AGG_WORK_ORDER,
                wo.getId(), wo.getUid(), wo.getCompanyId(), wo.getBranchId(),
                Map.of("woNumber", wo.getWoNumber(), "goodQty", goodQty, "unitCost", unitCost));

        audit.record(AuditEvent.of(AuditActions.WORKORDER_COMPLETE, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("goodQty", goodQty, "scrapQty", scrapQty,
                        "unitCost", unitCost, "relievedValue", relievedValue)));
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Close (residual variance clear — COMPLETED → CLOSED)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto close(String workOrderUid, CloseWorkOrderRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        if (wo.getStatus() != WorkOrderStatus.COMPLETED) {
            throw new IllegalStateException("Only COMPLETED work orders can be closed.");
        }

        // Residual WIP = debit − credit (may be positive or negative due to rounding)
        BigDecimal residual = wo.openWip();

        if (residual.abs().compareTo(BigDecimal.ZERO) > 0) {
            // GL: DR Manufacturing Variance / CR WIP (or reversed if negative)
            glPoster.postVarianceClear(wo, residual, req.postingDate(), principal.userId());
        }

        wo.close(residual, principal.userId());

        audit.record(AuditEvent.of(AuditActions.WORKORDER_CLOSE, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("varianceAmount", residual)));
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Cancel — reverse all issued components, FG receipts, labour/overhead (Finding 1 fix)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto cancel(String workOrderUid, String reason, LocalDate postingDate) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        WorkOrderStatus st = wo.getStatus();
        if (st == WorkOrderStatus.CLOSED || st == WorkOrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel a " + st + " work order.");
        }
        if (st == WorkOrderStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Cannot cancel a COMPLETED work order — close it first or contact a manager.");
        }

        LocalDate effectiveDate = postingDate != null ? postingDate : LocalDate.now();
        Long locationId = locationResolver.defaultLocationId(wo.getCompanyId(), wo.getBranchId());

        // --- 1. Reverse all issued components (stock + GL) ---
        List<WorkOrderComponent> compLines = components.findByWorkOrderIdOrderByLineNoAsc(wo.getId());
        List<ComponentCostLeg> costLegs = new ArrayList<>();

        for (WorkOrderComponent comp : compLines) {
            if (comp.getIssuedQty().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal issuedQty   = comp.getIssuedQty();
            BigDecimal issuedValue = comp.getIssuedValue();

            // Stock: PRODUCTION_ISSUE_REVERSAL (positive qty restores on-hand)
            stockPosting.post(
                    wo.getCompanyId(), wo.getBranchId(), locationId,
                    comp.getComponentProductId(),
                    issuedQty,
                    MovementType.PRODUCTION_ISSUE_REVERSAL,
                    null, "WORK_ORDER", wo.getUid(),
                    null, "WO " + wo.getWoNumber() + " cancel: component reversal",
                    Instant.now(), principal.userId(),
                    comp.getUnitCostAtIssue(), issuedValue.compareTo(BigDecimal.ZERO) > 0 ? issuedValue : null);

            // Valuation: restore on_hand_value at original value (cost-skipped lines have issuedValue=0)
            if (issuedValue.compareTo(BigDecimal.ZERO) > 0) {
                valuation.reverseIssue(wo.getCompanyId(), wo.getBranchId(),
                        comp.getComponentProductId(), issuedValue);
                costLegs.add(new ComponentCostLeg(comp.getComponentProductCode(), issuedValue));
            }

            // Reset component line
            comp.reverseIssue(issuedQty, issuedValue, principal.userId());
            wo.reverseWipDebit(issuedValue, principal.userId());
        }

        // GL: DR Inventory / CR WIP for costed components
        if (!costLegs.isEmpty()) {
            glPoster.postCancelIssueReversal(wo, costLegs, effectiveDate, principal.userId());
        }

        // --- 2. Reverse applied labour/overhead (GL only — no stock movement) ---
        BigDecimal labour   = wo.getLabourAppliedTotal();
        BigDecimal overhead = wo.getOverheadAppliedTotal();
        if (labour.add(overhead).compareTo(BigDecimal.ZERO) > 0) {
            glPoster.postCancelLabourOverheadReversal(wo, labour, overhead, effectiveDate, principal.userId());
            wo.reverseWipDebit(labour.add(overhead), principal.userId());
        }

        // --- 3. No FG receipt to reverse for IN_PROGRESS (COMPLETED is blocked above) ---
        //    wipCreditTotal should be 0 for non-COMPLETED orders. If it is somehow non-zero,
        //    reverse the credit leg to keep the WO accumulators consistent.
        BigDecimal wipCredit = wo.getWipCreditTotal();
        if (wipCredit.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("WorkOrderCostingServiceImpl.cancel: WO {} has wipCreditTotal={} in status {} — reversing credit.",
                    wo.getWoNumber(), wipCredit, st);
            glPoster.postCancelReceiptReversal(wo, wipCredit, effectiveDate, principal.userId());
            wo.reverseWipCredit(wipCredit, principal.userId());
        }

        // --- 4. Set status to CANCELLED ---
        wo.cancel(principal.userId());

        audit.record(AuditEvent.of(AuditActions.WORKORDER_CANCEL, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("reason", reason != null ? reason : "",
                               "componentLinesReversed", costLegs.size(),
                               "labourReversed", labour,
                               "overheadReversed", overhead)));

        log.info("WorkOrderCostingServiceImpl.cancel: WO {} cancelled with full reversal (company {}).",
                wo.getWoNumber(), wo.getCompanyId());
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Cost report
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public WorkOrderCostReportDto costReport(String workOrderUid) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        List<WorkOrderComponent> compLines = components.findByWorkOrderIdOrderByLineNoAsc(wo.getId());
        List<WorkOrderOperation> opLines   = operations.findByWorkOrderIdOrderBySeqNoAsc(wo.getId());

        return new WorkOrderCostReportDto(
                wo.getWoNumber(),
                wo.getFinishedProductCode(), wo.getFinishedProductName(),
                wo.getPlannedQty(), wo.getGoodQty(), wo.getScrapQty(),
                wo.getWipDebitTotal(), wo.getWipCreditTotal(),
                wo.getLabourAppliedTotal(), wo.getOverheadAppliedTotal(),
                wo.getComputedUnitCost(), wo.getVarianceAmount(), wo.isIncompleteCost(),
                compLines.stream().map(this::toCompDto).toList(),
                opLines.stream().map(this::toOpDto).toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WorkOrder requireWorkOrder(String uid) {
        return workOrders.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Work order not found."));
    }

    /**
     * Materialise BOM explosion into work_order_components lines (called lazily on first issue).
     * The BOM must already be pinned on the work order (done at release).
     */
    private List<WorkOrderComponent> materialiseComponents(WorkOrder wo, Long actorId) {
        if (wo.getBomUid() == null) {
            throw new IllegalStateException("Work order has no BOM pinned. Release with a BOM first.");
        }

        List<BomExplosionLeafDto> leaves = bomExplosion.explodeToLeaves(
                products.findById(wo.getFinishedProductId())
                        .orElseThrow(() -> new NotFoundException("Product not found for work order FG."))
                        .getUid(),
                wo.getPlannedQty(),
                true);

        if (leaves.isEmpty()) {
            throw new IllegalStateException("BOM has no leaf components to issue.");
        }

        List<WorkOrderComponent> result = new ArrayList<>();
        short lineNo = 1;
        for (BomExplosionLeafDto leaf : leaves) {
            WorkOrderComponent comp = new WorkOrderComponent(
                    wo.getId(), wo.getCompanyId(),
                    lineNo++,
                    leaf.componentProductId(), leaf.componentProductCode(), leaf.componentProductName(),
                    leaf.totalQuantity(), actorId);
            result.add(components.save(comp));
        }
        return result;
    }

    private WorkOrderDto toDto(WorkOrder wo) {
        return new WorkOrderDto(
                wo.getId(), wo.getUid(), wo.getWoNumber(), wo.getCompanyId(), wo.getBranchId(),
                wo.getFinishedProductId(), wo.getFinishedProductCode(), wo.getFinishedProductName(),
                wo.getBomId(), wo.getBomUid(),
                wo.getPlannedQty(), wo.getGoodQty(), wo.getScrapQty(),
                wo.getStatus(),
                wo.getWipDebitTotal(), wo.getWipCreditTotal(),
                wo.getLabourAppliedTotal(), wo.getOverheadAppliedTotal(),
                wo.getComputedUnitCost(), wo.getVarianceAmount(),
                wo.isIncompleteCost(), wo.getCostCentreValueId(), wo.getTargetLocationId(),
                wo.getPlannedDate(), wo.getReleasedAt(), wo.getCompletedAt(),
                wo.getClosedAt(), wo.getCancelledAt(), wo.getNotes(),
                wo.getCreatedAt(), wo.getCreatedBy(),
                List.of(), List.of());
    }

    private WorkOrderComponentDto toCompDto(WorkOrderComponent c) {
        return new WorkOrderComponentDto(
                c.getId(), c.getUid(), c.getWorkOrderId(), c.getLineNo(),
                c.getComponentProductId(), c.getComponentProductCode(), c.getComponentProductName(),
                c.getPlannedQty(), c.getIssuedQty(), c.getReturnedQty(), c.getScrapQty(),
                c.getIssuedValue(),
                c.getUnitCostAtIssue(), c.getUnitId(), c.isCostSkipped(), c.getStatus());
    }

    private WorkOrderOperationDto toOpDto(WorkOrderOperation op) {
        return new WorkOrderOperationDto(
                op.getId(), op.getUid(), op.getWorkOrderId(), op.getSeqNo(),
                op.getDescription(), op.getWorkCentre(),
                op.getLabourAmount(), op.getOverheadAmount(), op.isApplied());
    }
}
