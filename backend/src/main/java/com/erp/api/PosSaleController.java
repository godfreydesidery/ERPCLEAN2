package com.erp.api;

import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.service.PosSaleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS quick-sale endpoint (ADR-0029 D-5, FR-SD-15).
 * Creates and finalises a DIRECT invoice tagged to the session atomically.
 */
@RestController
@RequestMapping("/api/v1/pos/sales")
public class PosSaleController {

    private final PosSaleService posSaleService;

    public PosSaleController(PosSaleService posSaleService) {
        this.posSaleService = posSaleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.SALE.CREATE')")
    public SalesInvoiceDto processSale(@Valid @RequestBody PosSaleRequest request) {
        return posSaleService.processSale(request);
    }
}
