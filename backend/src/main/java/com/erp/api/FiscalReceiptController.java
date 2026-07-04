package com.erp.api;

import com.erp.modules.sales.domain.dto.FiscalReceiptDto;
import com.erp.modules.sales.service.FiscalReceiptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EFD/fiscal-receipt REST endpoints (ADR-0049 §3/§7), addressed by the invoice uid. Reuses the
 * existing {@code 'invoice'} scope target ({@code ScopeGuard.companyIdOf}) — no new scope key.
 *
 * <p>Permission codes (seeded in {@code R__seed_permissions.sql}): {@code FISCAL.MANAGE} (issue/retry),
 * {@code FISCAL.VIEW} (read).
 */
@RestController
@RequestMapping("/api/v1/sales-invoices")
public class FiscalReceiptController {

    private final FiscalReceiptService fiscalReceiptService;

    public FiscalReceiptController(FiscalReceiptService fiscalReceiptService) {
        this.fiscalReceiptService = fiscalReceiptService;
    }

    @PostMapping("/uid/{uid}/fiscal-receipt")
    @PreAuthorize("@perm.scoped(#uid,'invoice','FISCAL.MANAGE')")
    public FiscalReceiptDto issue(@PathVariable String uid) {
        return fiscalReceiptService.issue(uid);
    }

    @GetMapping("/uid/{uid}/fiscal-receipt")
    @PreAuthorize("@perm.scoped(#uid,'invoice','FISCAL.VIEW')")
    public FiscalReceiptDto getForInvoice(@PathVariable String uid) {
        return fiscalReceiptService.getForInvoice(uid);
    }
}
