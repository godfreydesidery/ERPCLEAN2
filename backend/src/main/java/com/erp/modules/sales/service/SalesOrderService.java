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

    /** Transitions to CANCELLED; releases remaining reservations. */
    void cancel(String orderUid, CancelSalesOrderRequest req);

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
