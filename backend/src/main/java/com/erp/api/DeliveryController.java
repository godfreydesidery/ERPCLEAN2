package com.erp.api;

import com.erp.modules.sales.domain.dto.CreateDeliveryRequest;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.service.DeliveryService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery lifecycle REST controller (ADR-0021 D-7/D-10).
 * Responses auto-wrapped by ApiResponseAdvice.
 * Permission codes seeded in V18__sales_orders.sql.
 */
@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.salesOrderUid(),'salesorder','SALES.DELIVERY.CREATE')")
    public DeliveryDto create(@Valid @RequestBody CreateDeliveryRequest request) {
        return deliveryService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'delivery','SALES.DELIVERY.VIEW')")
    public DeliveryDto getByUid(@PathVariable String uid) {
        return deliveryService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.DELIVERY.VIEW')")
    public ApiResponse<List<DeliveryDto>> list(@RequestParam Long companyId,
                                                Pageable pageable) {
        Page<DeliveryDto> page = deliveryService.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/for-order/{salesOrderUid}")
    @PreAuthorize("@perm.scoped(#salesOrderUid,'salesorder','SALES.DELIVERY.VIEW')")
    public List<DeliveryDto> listForOrder(@PathVariable String salesOrderUid) {
        return deliveryService.listForOrder(salesOrderUid);
    }

    /**
     * D-10 seam: generate a DRAFT invoice for all uninvoiced lines on this delivery.
     * The caller finalises via POST /api/v1/sales-invoices/uid/{uid}/finalize.
     */
    @PostMapping("/uid/{uid}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'delivery','SALES.DELIVERY.CREATE')")
    public SalesInvoiceDto createInvoiceFromDelivery(@PathVariable String uid) {
        return deliveryService.createInvoiceFromDelivery(uid);
    }
}
