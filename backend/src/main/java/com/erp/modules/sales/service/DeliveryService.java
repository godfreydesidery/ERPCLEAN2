package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreateDeliveryRequest;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DeliveryService {

    /**
     * Creates a delivery against a confirmed SO, issues stock reservation releases,
     * and publishes DELIVERY.CONFIRMED. Created in CONFIRMED status (v1).
     */
    DeliveryDto create(CreateDeliveryRequest req);

    DeliveryDto getByUid(String uid);

    Page<DeliveryDto> list(Long companyId, Pageable pageable);

    List<DeliveryDto> listForOrder(String salesOrderUid);

    /**
     * Creates a DRAFT invoice from this delivery's uninvoiced delivered qty (D-10).
     * The invoice has origin=SALES_ORDER and source_delivery_uid set.
     * The caller finalises via the standard invoice finalise endpoint.
     */
    SalesInvoiceDto createInvoiceFromDelivery(String deliveryUid);
}
