package com.erp.modules.manufacturing.service;

import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.modules.manufacturing.domain.dto.AddOperationRequest;
import com.erp.modules.manufacturing.domain.dto.CreateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.ReleaseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.UpdateWorkOrderRequest;
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
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;

import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work-order lifecycle: PLANNED → RELEASED → CANCELLED (ADR-0035 D-5).
 * Costing transitions (issue, apply, complete, close) in {@link WorkOrderCostingServiceImpl}.
 */
@Service
@Transactional
public class WorkOrderServiceImpl implements WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderServiceImpl.class);

    private final WorkOrderRepository          workOrders;
    private final WorkOrderComponentRepository components;
    private final WorkOrderOperationRepository operations;
    private final ProductRepository            products;
    private final BomRepository                boms;
    private final BranchRepository             branches;
    private final DimensionValueRepository     dimensionValues;
    private final WorkOrderNumberGenerator     numberGenerator;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;
    private final OutboxPublisher              outbox;
    /**
     * Injected lazily via setter to break the circular dependency:
     * WorkOrderServiceImpl → WorkOrderCostingServiceImpl → WorkOrderServiceImpl (mapper).
     * Spring resolves this via the setter after both beans are constructed.
     */
    private WorkOrderCostingService costingService;

    public WorkOrderServiceImpl(WorkOrderRepository workOrders,
                                 WorkOrderComponentRepository components,
                                 WorkOrderOperationRepository operations,
                                 ProductRepository products,
                                 BomRepository boms,
                                 BranchRepository branches,
                                 DimensionValueRepository dimensionValues,
                                 WorkOrderNumberGenerator numberGenerator,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 OutboxPublisher outbox) {
        this.workOrders      = workOrders;
        this.components      = components;
        this.operations      = operations;
        this.products        = products;
        this.boms            = boms;
        this.branches        = branches;
        this.dimensionValues = dimensionValues;
        this.numberGenerator = numberGenerator;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
        this.outbox          = outbox;
    }

    /** Setter injection to break the circular reference with WorkOrderCostingServiceImpl. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setCostingService(WorkOrderCostingService costingService) {
        this.costingService = costingService;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto create(CreateWorkOrderRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        Long companyId = principal.companyId();
        scopeGuard.assertCanActIn(principal, companyId);

        Product product = products.findByUid(req.finishedProductUid())
                .orElseThrow(() -> new NotFoundException("Product not found: " + req.finishedProductUid()));
        if (!product.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("Product does not belong to your company.");
        }

        Long branchId = branches.findByUid(req.branchUid())
                .orElseThrow(() -> new NotFoundException("Branch not found: " + req.branchUid()))
                .getId();

        String woNumber = numberGenerator.next(companyId);
        WorkOrder wo = new WorkOrder(companyId, branchId,
                product.getId(), product.getCode(), product.getName(),
                req.plannedQty(), woNumber);
        wo.setCreatedBy(principal.userId());

        if (req.plannedDate() != null)        { wo.updateDraft(req.plannedQty(), branchId, req.plannedDate(), null, req.notes(), principal.userId()); }
        if (req.notes() != null)              { wo.updateDraft(req.plannedQty(), branchId, req.plannedDate(), null, req.notes(), principal.userId()); }

        if (req.bomUid() != null) {
            Bom bom = requireBomByUid(req.bomUid(), companyId);
            wo.pinBom(bom.getId(), bom.getUid(), principal.userId());
        }

        Long costCentreValueId = null;
        if (req.costCentreValueUid() != null) {
            costCentreValueId = dimensionValues.findByUid(req.costCentreValueUid())
                    .orElseThrow(() -> new NotFoundException("CostCentreValue not found: " + req.costCentreValueUid()))
                    .getId();
        }
        wo.updateDraft(req.plannedQty(), branchId, req.plannedDate(), costCentreValueId, req.notes(), principal.userId());

        WorkOrder saved = workOrders.save(wo);

        audit.record(AuditEvent.of(AuditActions.WORKORDER_CREATE, "work_orders", saved.getId(), saved.getUid()));

        log.info("Work order {} created for product {} (company {}).",
                saved.getWoNumber(), product.getCode(), companyId);
        return toDto(saved);
    }

    // -------------------------------------------------------------------------
    // Update (PLANNED only)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto update(String uid, UpdateWorkOrderRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(uid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        if (wo.getStatus() != WorkOrderStatus.PLANNED) {
            throw new IllegalStateException("Work order can only be edited while PLANNED (BR-MFG-04).");
        }

        Long branchId = wo.getBranchId();
        if (req.branchUid() != null) {
            branchId = branches.findByUid(req.branchUid())
                    .orElseThrow(() -> new NotFoundException("Branch not found: " + req.branchUid()))
                    .getId();
        }

        Long costCentreValueId = wo.getCostCentreValueId();
        if (req.costCentreValueUid() != null) {
            costCentreValueId = dimensionValues.findByUid(req.costCentreValueUid())
                    .orElseThrow(() -> new NotFoundException("CostCentreValue not found: " + req.costCentreValueUid()))
                    .getId();
        }

        BigDecimal newQty = req.plannedQty() != null ? req.plannedQty() : wo.getPlannedQty();
        wo.updateDraft(newQty, branchId,
                req.plannedDate() != null ? req.plannedDate() : wo.getPlannedDate(),
                costCentreValueId,
                req.notes() != null ? req.notes() : wo.getNotes(),
                principal.userId());

        if (req.bomUid() != null) {
            Bom bom = requireBomByUid(req.bomUid(), wo.getCompanyId());
            wo.pinBom(bom.getId(), bom.getUid(), principal.userId());
        }

        audit.record(AuditEvent.of(AuditActions.WORKORDER_UPDATE, "work_orders", wo.getId(), wo.getUid()));
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Release (PLANNED → RELEASED)
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto release(String uid, ReleaseWorkOrderRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(uid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        if (wo.getStatus() != WorkOrderStatus.PLANNED) {
            throw new IllegalStateException("Only PLANNED work orders can be released.");
        }

        // Resolve BOM — prefer request override, then pinned, then active BOM for product (BR-MFG-02)
        String effectiveBomUid = req != null && req.bomUid() != null ? req.bomUid() : wo.getBomUid();
        Bom bom;
        if (effectiveBomUid != null) {
            bom = requireBomByUid(effectiveBomUid, wo.getCompanyId());
        } else {
            bom = boms.findByParentProductIdAndStatus(wo.getFinishedProductId(), BomStatus.ACTIVE)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No ACTIVE BOM for product " + wo.getFinishedProductCode()
                            + ". Pin a BOM before releasing (BR-MFG-02)."));
        }

        wo.release(bom.getId(), bom.getUid(), principal.userId());

        // Emit informational WORK_ORDER.RELEASED outbox event (ADR-0035 D-11)
        outbox.publish(DomainEventType.WORK_ORDER_RELEASED, DomainEventType.AGG_WORK_ORDER,
                wo.getId(), wo.getUid(), wo.getCompanyId(), wo.getBranchId(),
                Map.of("woNumber", wo.getWoNumber(), "bomUid", bom.getUid(),
                        "plannedQty", wo.getPlannedQty()));

        audit.record(AuditEvent.of(AuditActions.WORKORDER_RELEASE, "work_orders", wo.getId(), wo.getUid())
                .detail(Map.of("bomUid", bom.getUid(), "bomVersion", bom.getVersionNo())));

        log.info("Work order {} released with BOM {} (company {}).",
                wo.getWoNumber(), bom.getUid(), wo.getCompanyId());
        return toDto(wo);
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderDto cancel(String uid, String reason) {
        // Delegate to costingService so that all issued-component stock + GL reversals are performed
        // atomically with the status change (ADR-0035 D-5, Finding 1 fix).
        return costingService.cancel(uid, reason, null);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public WorkOrderDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(uid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());
        return toDtoWithLines(wo);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WorkOrderDto> list(Long companyId, WorkOrderStatus status, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        Page<WorkOrder> page = status != null
                ? workOrders.findByCompanyIdAndStatus(companyId, status, pageable)
                : workOrders.findByCompanyId(companyId, pageable);
        return page.map(this::toDto);
    }

    // -------------------------------------------------------------------------
    // Operations management
    // -------------------------------------------------------------------------

    @Override
    public WorkOrderOperationDto addOperation(String workOrderUid, AddOperationRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        WorkOrderStatus st = wo.getStatus();
        if (st == WorkOrderStatus.CLOSED || st == WorkOrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot add operations to a " + st + " work order.");
        }

        WorkOrderOperation op = new WorkOrderOperation(
                wo.getId(), wo.getCompanyId(),
                req.seqNo().shortValue(),
                req.description(),
                req.workCentre(),
                req.labourAmount() != null ? req.labourAmount() : BigDecimal.ZERO,
                req.overheadAmount() != null ? req.overheadAmount() : BigDecimal.ZERO,
                principal.userId()
        );
        WorkOrderOperation saved = operations.save(op);
        return toOpDto(saved);
    }

    @Override
    public void removeOperation(String workOrderUid, String operationUid) {
        RequestContext.Principal principal = RequestContext.get();
        WorkOrder wo = requireWorkOrder(workOrderUid);
        scopeGuard.assertCanActIn(principal, wo.getCompanyId());

        WorkOrderOperation op = operations.findByWorkOrderIdAndUid(wo.getId(), operationUid)
                .orElseThrow(() -> new NotFoundException("Operation not found: " + operationUid));
        if (op.isApplied()) {
            throw new IllegalStateException("Cannot remove an already-applied operation.");
        }
        operations.delete(op);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WorkOrder requireWorkOrder(String uid) {
        return workOrders.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Work order not found: " + uid));
    }

    private Bom requireBomByUid(String bomUid, Long companyId) {
        Bom bom = boms.findByUid(bomUid)
                .orElseThrow(() -> new NotFoundException("BOM not found: " + bomUid));
        if (!bom.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("BOM does not belong to your company.");
        }
        return bom;
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    WorkOrderDto toDto(WorkOrder wo) {
        return new WorkOrderDto(
                wo.getId(), wo.getUid(), wo.getWoNumber(), wo.getCompanyId(), wo.getBranchId(),
                wo.getFinishedProductId(), wo.getFinishedProductCode(), wo.getFinishedProductName(),
                wo.getBomId(), wo.getBomUid(),
                wo.getPlannedQty(), wo.getGoodQty(), wo.getScrapQty(),
                wo.getStatus(),
                wo.getWipDebitTotal(), wo.getWipCreditTotal(),
                wo.getLabourAppliedTotal(), wo.getOverheadAppliedTotal(),
                wo.getComputedUnitCost(), wo.getVarianceAmount(),
                wo.isIncompleteCost(), wo.getCostCentreValueId(),
                wo.getPlannedDate(), wo.getReleasedAt(), wo.getCompletedAt(),
                wo.getClosedAt(), wo.getCancelledAt(), wo.getNotes(),
                wo.getCreatedAt(), wo.getCreatedBy(),
                List.of(), List.of());
    }

    private WorkOrderDto toDtoWithLines(WorkOrder wo) {
        List<WorkOrderComponentDto> compDtos = components
                .findByWorkOrderIdOrderByLineNoAsc(wo.getId()).stream()
                .map(this::toCompDto).toList();
        List<WorkOrderOperationDto> opDtos = operations
                .findByWorkOrderIdOrderBySeqNoAsc(wo.getId()).stream()
                .map(this::toOpDto).toList();
        return new WorkOrderDto(
                wo.getId(), wo.getUid(), wo.getWoNumber(), wo.getCompanyId(), wo.getBranchId(),
                wo.getFinishedProductId(), wo.getFinishedProductCode(), wo.getFinishedProductName(),
                wo.getBomId(), wo.getBomUid(),
                wo.getPlannedQty(), wo.getGoodQty(), wo.getScrapQty(),
                wo.getStatus(),
                wo.getWipDebitTotal(), wo.getWipCreditTotal(),
                wo.getLabourAppliedTotal(), wo.getOverheadAppliedTotal(),
                wo.getComputedUnitCost(), wo.getVarianceAmount(),
                wo.isIncompleteCost(), wo.getCostCentreValueId(),
                wo.getPlannedDate(), wo.getReleasedAt(), wo.getCompletedAt(),
                wo.getClosedAt(), wo.getCancelledAt(), wo.getNotes(),
                wo.getCreatedAt(), wo.getCreatedBy(),
                compDtos, opDtos);
    }

    WorkOrderComponentDto toCompDto(WorkOrderComponent c) {
        return new WorkOrderComponentDto(
                c.getId(), c.getUid(), c.getWorkOrderId(), c.getLineNo(),
                c.getComponentProductId(), c.getComponentProductCode(), c.getComponentProductName(),
                c.getPlannedQty(), c.getIssuedQty(), c.getIssuedValue(),
                c.getUnitCostAtIssue(), c.isCostSkipped(), c.getStatus());
    }

    WorkOrderOperationDto toOpDto(WorkOrderOperation op) {
        return new WorkOrderOperationDto(
                op.getId(), op.getUid(), op.getWorkOrderId(), op.getSeqNo(),
                op.getDescription(), op.getWorkCentre(),
                op.getLabourAmount(), op.getOverheadAmount(), op.isApplied());
    }
}
