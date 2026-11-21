package com.erp.api;

import com.erp.modules.sales.domain.dto.PosSaleReturnDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnsDto;
import com.erp.modules.sales.service.PosSaleReturnService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS "refund by item" (ADR-0072) — return chosen items of a finalised POS sale, refunded in CASH
 * from the till drawer. The whole-sale reverse stays on {@code PosSaleController}; once a sale has any
 * item return, that reverse is refused (409).
 *
 * <p>Gate: {@code POS.SALE.VOID} <em>or</em> {@code SALES.INVOICE.VOID} scoped to the sale's company —
 * exactly the reverse endpoint's. As there, being past the gate only means the caller may ask;
 * {@code PosSaleReturnService#returnItems} then enforces the own-drawer rule and re-verifies the
 * manager step-up ({@code authorisedByUid}). No new permission codes.
 */
@RestController
@RequestMapping("/api/v1/pos")
public class PosSaleReturnController {

    private final PosSaleReturnService returnService;

    public PosSaleReturnController(PosSaleReturnService returnService) {
        this.returnService = returnService;
    }

    /**
     * Return items and refund them in cash. Send the till's {@code Idempotency-Key} and reuse the SAME
     * value on a retry: the original return is answered instead of refunding twice.
     *
     * <p>201 with the return (refund slip data). 409 with a friendly message when the sale cannot take
     * the return (reversed, session closed, more than remains, AR allocations, …); 403 when the
     * own-drawer / supervisor rule refuses; 400 for a malformed body.
     */
    @PostMapping("/sales/uid/{uid}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'invoice','POS.SALE.VOID')"
            + " or @perm.scoped(#uid,'invoice','SALES.INVOICE.VOID')")
    public PosSaleReturnDto returnItems(
            @PathVariable String uid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PosSaleReturnRequest request) {
        return returnService.returnItems(uid, idempotencyKey, request);
    }

    /**
     * The sale's returns plus, per sold line, sold / returned / remaining quantity — what the
     * "Return items" screen and the receipt need. Whoever may ring or reverse a sale may read this.
     */
    @GetMapping("/sales/uid/{uid}/returns")
    @PreAuthorize("@perm.scoped(#uid,'invoice','POS.SALE.CREATE')"
            + " or @perm.scoped(#uid,'invoice','POS.SALE.VOID')"
            + " or @perm.scoped(#uid,'invoice','SALES.INVOICE.VOID')")
    public PosSaleReturnsDto listForSale(@PathVariable String uid) {
        return returnService.listForSale(uid);
    }

    /**
     * One return, for reprinting its (non-fiscal) refund slip. Resolved inside the caller's active
     * company, then scope-checked from the loaded row.
     */
    @GetMapping("/returns/uid/{uid}")
    @PreAuthorize("@perm.has('POS.SALE.CREATE') or @perm.has('POS.SALE.VOID')"
            + " or @perm.has('SALES.INVOICE.VOID')")
    public PosSaleReturnDto getByUid(@PathVariable String uid) {
        return returnService.getByUid(uid);
    }
}
