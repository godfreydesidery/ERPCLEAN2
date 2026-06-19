package com.erp.api;

import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.service.PosSaleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * Ring a POS sale. Supply an {@code Idempotency-Key} header (optional) and reuse the SAME value
     * when retrying an ambiguous POST — the original sale is returned instead of double-posting
     * (ADR-0042 D-1). Omitting the header preserves the previous behaviour.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.SALE.CREATE')")
    public SalesInvoiceDto processSale(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PosSaleRequest request) {
        return posSaleService.processSale(idempotencyKey, request);
    }

    /**
     * Reverse (void) a POS sale at the till — refund / correct a mis-rung sale (ADR-0042 D-2).
     * Delegates to the invoice void (reverses revenue/VAT/cash + stock); requires the sale's session
     * to still be OPEN. Gated by {@code POS.SALE.VOID}.
     */
    @PostMapping("/uid/{uid}/reverse")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','POS.SALE.VOID')")
    public void reverseSale(@PathVariable String uid, @Valid @RequestBody VoidInvoiceRequest request) {
        posSaleService.reverseSale(uid, request.reason());
    }
}
