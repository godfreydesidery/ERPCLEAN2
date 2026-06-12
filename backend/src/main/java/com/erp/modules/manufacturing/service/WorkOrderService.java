package com.erp.modules.manufacturing.service;

import com.erp.modules.manufacturing.domain.dto.AddOperationRequest;
import com.erp.modules.manufacturing.domain.dto.CreateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.ReleaseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.UpdateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.dto.WorkOrderOperationDto;
import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Work order lifecycle service — PLANNED / RELEASED / CANCELLED state management (ADR-0035 D-5).
 * Costing operations (issue, apply cost, complete, close) are in {@link WorkOrderCostingService}.
 */
public interface WorkOrderService {

    WorkOrderDto create(CreateWorkOrderRequest req);

    WorkOrderDto update(String uid, UpdateWorkOrderRequest req);

    WorkOrderDto release(String uid, ReleaseWorkOrderRequest req);

    WorkOrderDto cancel(String uid, String reason);

    WorkOrderDto getByUid(String uid);

    Page<WorkOrderDto> list(Long companyId, WorkOrderStatus status, Pageable pageable);

    WorkOrderOperationDto addOperation(String workOrderUid, AddOperationRequest req);

    void removeOperation(String workOrderUid, String operationUid);
}
