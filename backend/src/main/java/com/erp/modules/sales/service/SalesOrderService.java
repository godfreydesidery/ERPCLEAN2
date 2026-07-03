package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CancelSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SalesOrderService {

    SalesOrderDto create(CreateSalesOrderRequest req);

    SalesOrderDto getByUid(String uid);

    Page<SalesOrderDto> list(Long companyId, Pageable pageable);

    SalesOrderLineDto addLine(String orderUid, AddSalesOrderLineRequest req);

    void removeLine(String orderUid, String lineUid);

    List<SalesOrderLineDto> listLines(String orderUid);

    /** Transitions DRAFT → CONFIRMED; reserves stock for all lines. */
    SalesOrderDto confirm(String orderUid);

    /**
     * Submits a DRAFT order to the approvals engine (document type {@code SALES_ORDER}).
     * Idempotent: a re-call while a PENDING request already exists is a no-op that just returns
     * the current dto. Nothing is persisted on the SalesOrder entity — the engine is the source
     * of truth for approval state (surfaced back via {@link SalesOrderDto#approvalStatus()}).
     */
    SalesOrderDto submitForApproval(String orderUid);

    /** Transitions to CANCELLED; releases remaining reservations. */
    void cancel(String orderUid, CancelSalesOrderRequest req);

    /**
     * Sets or changes the order's sales agent — fixing an agentless (or wrong-agent) order so it
     * can be invoiced. Allowed only in pre-invoice states (DRAFT, CONFIRMED, PARTIALLY_FULFILLED,
     * FULFILLED); rejected once the order is invoiced, closed, or cancelled.
     */
    SalesOrderDto setAgent(String uid, String agentUid);

    /**
     * Creates a SalesOrder from an accepted quotation — called internally by QuotationService.
     * Copies lines + pricing; allocates SO-####.
     */
    SalesOrderDto createFromQuotation(String quotationUid);

    /**
     * Recomputes and persists the SO status after a delivery or invoicing action.
     * Called by DeliveryService and invoicing paths (D-2 rollup function).
     */
    void recomputeStatus(Long salesOrderId);
}
