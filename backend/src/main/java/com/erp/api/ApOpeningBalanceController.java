package com.erp.api;

import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.service.ApOpeningBalanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AP opening balance entry (ADR-0015 D-8).
 * Creates an OPENING_BALANCE source bill; posts DR Opening Balance Equity / CR AP synchronously.
 * Permission: AP.OPENING.SET.
 */
@RestController
@RequestMapping("/api/v1/ap/opening-balance")
public class ApOpeningBalanceController {

    private final ApOpeningBalanceService service;

    public ApOpeningBalanceController(ApOpeningBalanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AP.OPENING.SET')")
    public SupplierBillDto set(@Valid @RequestBody SetApOpeningBalanceRequest req) {
        return service.setOpeningBalance(req);
    }
}
