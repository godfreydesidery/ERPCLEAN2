package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.service.PurchaseOrderService;
import com.erp.modules.sales.domain.dto.DropshipFulfilledPayload;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.domain.enums.FulfilmentMode;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drop-ship seam: flags SO lines and raises POs (ADR-0029 D-3).
 */
@Service
@Transactional
public class DropshipServiceImpl implements DropshipService {

    private final SalesOrderRepository      salesOrders;
    private final SalesOrderLineRepository  soLines;
    private final PurchaseOrderRepository   purchaseOrders;
    private final PurchaseOrderService      purchaseOrderService;
    private final ProductRepository         products;
    private final UnitOfMeasureRepository   units;
    private final SupplierRepository        suppliers;
    private final CompanyRepository         companies;
    private final OutboxPublisher           outbox;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public DropshipServiceImpl(SalesOrderRepository salesOrders,
                                SalesOrderLineRepository soLines,
                                PurchaseOrderRepository purchaseOrders,
                                PurchaseOrderService purchaseOrderService,
                                ProductRepository products,
                                UnitOfMeasureRepository units,
                                SupplierRepository suppliers,
                                CompanyRepository companies,
                                OutboxPublisher outbox,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.salesOrders          = salesOrders;
        this.soLines              = soLines;
        this.purchaseOrders       = purchaseOrders;
        this.purchaseOrderService = purchaseOrderService;
        this.products             = products;
        this.units                = units;
        this.suppliers            = suppliers;
        this.companies            = companies;
        this.outbox               = outbox;
        this.scopeGuard           = scopeGuard;
        this.audit                = audit;
    }

    @Override
    public SalesOrderLineDto flagDropShip(String orderUid, String lineUid, String supplierUid) {
        var order = salesOrders.findByUid(orderUid)
                .orElseThrow(() -> NotFoundException.of("SalesOrder", orderUid));
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());

        var line = soLines.findByUidAndSalesOrderId(lineUid, order.getId())
                .orElseThrow(() -> NotFoundException.of("SalesOrderLine", lineUid));

        if (line.getFulfilmentMode() == FulfilmentMode.DROP_SHIP) {
            throw new ConflictException("Line " + lineUid + " is already flagged as DROP_SHIP.");
        }

        var supplier = suppliers.findByUid(supplierUid)
                .orElseThrow(() -> NotFoundException.of("Supplier", supplierUid));
        var product = products.findById(line.getProductId())
                .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(line.getProductId())));
        var unit = units.findById(line.getUnitId())
                .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(line.getUnitId())));
        var company = companies.findById(order.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(order.getCompanyId())));

        // Raise the drop-ship PO
        var poLineReq = new AddPurchaseOrderLineRequest(
                product.getUid(), unit.getUid(),
                line.getQtyOrderedBase(), BigDecimal.ZERO, "Drop-ship for SO " + orderUid);
        var poDto = purchaseOrderService.create(new CreatePurchaseOrderRequest(
                company.getUid(), supplierUid, order.getCurrency().value(),
                "Drop-ship for SO " + orderUid, null, List.of(poLineReq)));

        // Stamp the drop-ship PO's ship_to_customer_id and source SO uid on the PO entity
        var poEntity = purchaseOrders.findByUid(poDto.uid())
                .orElseThrow(() -> NotFoundException.of("PurchaseOrder", poDto.uid()));
        poEntity.setShipToCustomerId(order.getCustomerId());
        poEntity.setSourceSalesOrderUid(orderUid);
        poEntity.setUpdatedAt(Instant.now());
        poEntity.setUpdatedBy(actorId());

        // Update the SO line
        line.setFulfilmentMode(FulfilmentMode.DROP_SHIP);
        line.setDropshipSupplierId(supplier.getId());
        line.setDropshipPoUid(poDto.uid());
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.DROPSHIP_FLAG, "sales_order_lines",
                line.getId(), line.getUid())
                .detail(Map.of("supplierUid", supplierUid, "dropshipPoUid", poDto.uid())));

        return SalesOrderLineDto.from(line);
    }

    @Override
    public void recordFulfilled(String salesOrderLineUid, String purchaseOrderUid,
                                BigDecimal qtyBase, BigDecimal supplierUnitCost,
                                String currency) {
        var line = soLines.findByUid(salesOrderLineUid)
                .orElseThrow(() -> NotFoundException.of("SalesOrderLine", salesOrderLineUid));
        scopeGuard.assertCanActIn(RequestContext.get(), line.getCompanyId());

        if (line.getFulfilmentMode() != FulfilmentMode.DROP_SHIP) {
            throw new ConflictException("Line " + salesOrderLineUid + " is not a DROP_SHIP line.");
        }

        // Store the actual supplier cost on the line for COGS posting
        line.setDropshipUnitCostAmount(supplierUnitCost);
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        // Publish DROPSHIP.FULFILLED for the GL handler
        outbox.publish(DomainEventType.DROPSHIP_FULFILLED, DomainEventType.AGG_SALES_ORDER,
                line.getSalesOrderId(), salesOrderLineUid,
                line.getCompanyId(), line.getBranchId(),
                new DropshipFulfilledPayload(salesOrderLineUid, purchaseOrderUid,
                        line.getCompanyId(), line.getBranchId(),
                        line.getProductId(), qtyBase, supplierUnitCost, currency));

        audit.record(AuditEvent.of(AuditActions.DROPSHIP_FULFILLED, "sales_order_lines",
                line.getId(), line.getUid())
                .detail(Map.of("purchaseOrderUid", purchaseOrderUid,
                               "qtyBase", qtyBase.toPlainString(),
                               "supplierCost", supplierUnitCost.toPlainString())));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }
}
