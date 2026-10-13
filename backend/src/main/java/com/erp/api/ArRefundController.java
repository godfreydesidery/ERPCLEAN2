package com.erp.api;

import com.erp.modules.ar.domain.dto.ArRefundDto;
import com.erp.modules.ar.domain.dto.RefundCustomerRequest;
import com.erp.modules.ar.service.ArRefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer refunds (ARC-11): pay back money held on account on a receipt (an over-payment or a
 * deposit) or an unused credit note, in cash or by bank. Gated AR.REFUND (finance seats); the
 * company scope is checked against the loaded receipt / credit note in the service.
 */
@RestController
@RequestMapping("/api/v1/ar/refunds")
public class ArRefundController {

    private final ArRefundService service;

    public ArRefundController(ArRefundService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AR.REFUND')")
    public ArRefundDto refund(@Valid @RequestBody RefundCustomerRequest req) {
        return service.refund(req);
    }
}
