package com.erp.api;

import com.erp.modules.sales.domain.dto.PosSaleConflictDto;
import com.erp.modules.sales.domain.dto.PosSaleLookupDto;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.service.PosSaleService;
import com.erp.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * Carries the machine-readable outcome of a refused sale. A client should branch on this (or on
     * the HTTP status, or on {@code data.code}) and NEVER on the message text.
     */
    public static final String POS_SALE_STATUS_HEADER = "X-Pos-Sale-Status";

    private final PosSaleService posSaleService;

    public PosSaleController(PosSaleService posSaleService) {
        this.posSaleService = posSaleService;
    }

    /**
     * Ring a POS sale. Supply an {@code Idempotency-Key} header (optional) and reuse the SAME value
     * when retrying an ambiguous POST — the original sale is returned instead of double-posting
     * (ADR-0042 D-1). Omitting the header preserves the previous behaviour.
     *
     * <p>Refusals are no longer all HTTP 409 (K11). See {@link #handlePosSaleFlow} for the mapping.
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
     * "Did the sale I sent under this reference actually post?" — the cheap read path the till was
     * missing (K11).
     *
     * <p>Without it, the only way to ask was to re-POST the whole sale, which re-ran every business
     * guard against data that may have drifted since; days later the answer came back as "the
     * session is closed", which is not an answer, so the unfinished-sale dialog re-armed forever.
     * This reads the idempotency marker and nothing else, so it stays valid long after the shift
     * ended and it can never post anything.
     *
     * <p>Verdicts: {@code POSTED} (with the invoice reference), {@code NEVER_POSTED}, {@code UNKNOWN}.
     * On {@code NEVER_POSTED} the till must ring the sale again <b>reusing the same key</b>, so an
     * attempt that commits a moment later still cannot become a second sale.
     *
     * <p>Gated by {@code POS.SALE.CREATE}: whoever may ring a sale may ask what became of it, and no
     * new permission code is introduced.
     */
    @GetMapping("/idempotency/{key}")
    @PreAuthorize("@perm.has('POS.SALE.CREATE')")
    public PosSaleLookupDto lookupByIdempotencyKey(@PathVariable String key) {
        return posSaleService.lookupByIdempotencyKey(key);
    }

    /**
     * Reverse (void) a POS sale at the till — refund / correct a mis-rung sale (ADR-0042 D-2).
     * Delegates to the invoice void (reverses revenue/VAT/cash + stock); requires the sale's session
     * to still be OPEN.
     *
     * <p><b>Gate (K1 follow-up):</b> {@code POS.SALE.VOID} <em>or</em> {@code SALES.INVOICE.VOID}.
     * The till's step-up policy names {@code SALES.INVOICE.VOID} as the authority for a refund, and
     * an endpoint that accepted only {@code POS.SALE.VOID} disagreed with it: a supervisor holding
     * exactly the code the client asks a manager to prove was refused at the door — the
     * route-guard/endpoint-parity trap, where a 403 reads to the operator as "the approval didn't
     * work". Both codes are seeded; neither is new.
     *
     * <p>Being past this gate is NOT permission to reverse the sale. It only means the caller may
     * ask. {@code PosSaleService#reverseSale} then decides on the two things a controller cannot
     * see: whose session the sale belongs to, and whether a real manager approved it. Body-level
     * authority ({@code authorisedByUid}) is re-verified there, never trusted from the request.
     */
    @PostMapping("/uid/{uid}/reverse")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','POS.SALE.VOID')"
            + " or @perm.scoped(#uid,'invoice','SALES.INVOICE.VOID')")
    public void reverseSale(@PathVariable String uid, @Valid @RequestBody VoidInvoiceRequest request) {
        posSaleService.reverseSale(uid, request);
    }

    /**
     * Splits the one 409 that used to mean four different things into three distinct, unmistakable
     * outcomes (K11 cause 2):
     *
     * <ul>
     *   <li>{@code IN_FLIGHT} → <b>409 Conflict</b>. An earlier attempt with the same key has not
     *       finished. The ONLY case in which the till should keep a pending sale armed.</li>
     *   <li>{@code REJECTED} → <b>422 Unprocessable Entity</b>. A business rule refused the sale and
     *       nothing was written. The till must NOT arm a pending sale — a first-attempt under-tender
     *       or out-of-stock used to create a ghost that blocked the drawer with nothing in flight.</li>
     *   <li>{@code STALE_REPLAY} → <b>410 Gone</b>. The pending sale is too old to complete; discard
     *       it and ring again.</li>
     * </ul>
     *
     * <p>Three separate status codes means a client that reads only the status already behaves
     * correctly; the header and {@code data.code} carry the same value for clients that prefer an
     * explicit token. The friendly text is in {@code errors[0]} as on every other endpoint.
     *
     * <p>{@code data.errorCode} adds the refusing rule's own token when it has one (e.g.
     * {@code DISCOUNT_APPROVAL_REQUIRED}), so a client can offer that rule's remedy without matching
     * English prose (UAT finding #13). Null for refusals that carry no token.
     *
     * <p>Declared on this controller (not the global advice) so the mapping applies to the sale flow
     * alone — {@code reverseSale}'s own conflicts keep their existing 409.
     */
    @ExceptionHandler(PosSaleFlowException.class)
    public ResponseEntity<ApiResponse<PosSaleConflictDto>> handlePosSaleFlow(PosSaleFlowException ex) {
        HttpStatus status = switch (ex.getStatus()) {
            case IN_FLIGHT    -> HttpStatus.CONFLICT;
            case REJECTED     -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STALE_REPLAY -> HttpStatus.GONE;
        };
        var body = new ApiResponse<>(
                new PosSaleConflictDto(ex.getStatus(), ex.getMessage(), ex.getErrorCode()),
                List.of(ex.getMessage()),
                null);
        return ResponseEntity.status(status)
                .header(POS_SALE_STATUS_HEADER, ex.getStatus().name())
                .body(body);
    }
}
