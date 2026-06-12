package com.erp.modules.manufacturing.service;

import com.erp.modules.manufacturing.domain.dto.ApplyCostRequest;
import com.erp.modules.manufacturing.domain.dto.CloseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CompleteWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderCostReportDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;

/**
 * Costing transitions for work orders (ADR-0035 D-5):
 * issue components → apply labour/overhead → complete (FG receipt) → close (variance clear).
 * Each method is synchronous: stock + GL posting happens inside the same TX.
 */
public interface WorkOrderCostingService {

    /** Issue BOM components from stock into WIP. RELEASED → IN_PROGRESS on first issue. */
    WorkOrderDto issueComponents(String workOrderUid, IssueComponentsRequest req);

    /** Apply labour and/or overhead amounts to WIP. */
    WorkOrderDto applyCost(String workOrderUid, ApplyCostRequest req);

    /** Receive finished goods; relieve WIP at computed cost; transition to COMPLETED. */
    WorkOrderDto complete(String workOrderUid, CompleteWorkOrderRequest req);

    /** Clear residual WIP to Manufacturing Variance; transition to CLOSED. */
    WorkOrderDto close(String workOrderUid, CloseWorkOrderRequest req);

    /** Full cost report: header + component issues + operations. */
    WorkOrderCostReportDto costReport(String workOrderUid);
}
