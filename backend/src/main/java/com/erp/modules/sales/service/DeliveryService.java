package com.erp.modules.sales.service;

import java.math.BigDecimal;
import java.util.Map;
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

    /**
     * SAL-07: hands back the quantities an order-billed invoice took off a delivery, when that
     * invoice is voided — the delivery-line and sales-order-line {@code qty_invoiced_base} are
     * decremented and the order status recomputed, so the goods can be invoiced again.
     *
     * <p>Called inside the void's transaction by the invoice service only; it performs no scope
     * check of its own (the void already did).
     *
     * @param deliveryUid           the invoice's {@code source_delivery_uid}
     * @param invoicedBaseByProduct base quantity the voided invoice billed, per product id
     */
    void releaseInvoicedQuantities(String deliveryUid, Map<Long, BigDecimal> invoicedBaseByProduct);
}
