package com.erp.api;

import com.erp.modules.sales.domain.dto.CreateSalesReturnRequest;
import com.erp.modules.sales.domain.dto.SalesReturnDto;
import com.erp.modules.sales.service.SalesReturnService;
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
 * Sales return / RMA REST controller (ADR-0021 D-11, Stage 2).
 * Responses auto-wrapped by ApiResponseAdvice.
 * Permission codes seeded in V18__sales_orders.sql.
 */
@RestController
@RequestMapping("/api/v1/sales-returns")
public class SalesReturnController {

    private final SalesReturnService salesReturnService;

    public SalesReturnController(SalesReturnService salesReturnService) {
        this.salesReturnService = salesReturnService;
    }

    /**
     * Create a return against a delivery (FR-SO-14, BR-SO-11).
     * Scoped to the delivery's owning company via the delivery uid.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.deliveryUid(),'delivery','SALES.RETURN.CREATE')")
    public SalesReturnDto create(@Valid @RequestBody CreateSalesReturnRequest request) {
        return salesReturnService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'salesreturn','SALES.RETURN.VIEW')")
    public SalesReturnDto getByUid(@PathVariable String uid) {
        return salesReturnService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.RETURN.VIEW')")
    public ApiResponse<List<SalesReturnDto>> listByCompany(@RequestParam Long companyId,
                                                            Pageable pageable) {
        Page<SalesReturnDto> page = salesReturnService.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/for-delivery/{deliveryUid}")
    @PreAuthorize("@perm.scoped(#deliveryUid,'delivery','SALES.RETURN.VIEW')")
    public ApiResponse<List<SalesReturnDto>> listByDelivery(@PathVariable String deliveryUid,
                                                             Pageable pageable) {
        Page<SalesReturnDto> page = salesReturnService.listByDelivery(deliveryUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
