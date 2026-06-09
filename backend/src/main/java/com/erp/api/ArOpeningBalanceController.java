package com.erp.api;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.service.ArOpeningBalanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR opening balance entry (go-live data load, ADR-0014, FR-AR-15).
 * Permission code seeded in V11__accounts_receivable.sql: AR.OPENING.SET.
 */
@RestController
@RequestMapping("/api/v1/ar/opening-balances")
public class ArOpeningBalanceController {

    private final ArOpeningBalanceService service;

    public ArOpeningBalanceController(ArOpeningBalanceService service) {
        this.service = service;
    }

    /**
     * Enter an AR opening balance for a customer. Scoped on the request body's companyUid.
     * Creates an ar_invoices row (source=OPENING_BALANCE) and posts to GL synchronously.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','AR.OPENING.SET')")
    public ArInvoiceDto setOpeningBalance(@Valid @RequestBody SetOpeningBalanceRequest req) {
        return service.setOpeningBalance(req);
    }
}
