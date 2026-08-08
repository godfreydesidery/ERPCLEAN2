package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO to void a finalised invoice (ADR-0008 D-7, FR-SALES-22).
 * Requires SALES.INVOICE.VOID permission. The invoice retains its number (void ≠ delete).
 *
 * <p><b>{@code authorisedByUid}</b> (K1 follow-up) carries the uid returned by a successful manager
 * step-up ({@code POST /api/v1/auth/verify-authority}). It exists because a till-only control is
 * decoration: the POS reversal dialog asked for a manager password, but nothing about that prompt
 * reached the server, so curl — or a second client, or a modified build — skipped the manager
 * entirely. The POS reversal path re-verifies this uid server-side (see
 * {@code PosSaleServiceImpl#reverseSale}); the back-office void does not use it, because that
 * endpoint is already gated on the supervisor's own {@code SALES.INVOICE.VOID}.
 *
 * <p>Optional on the wire: a supervisor reversing a sale on their own authority has nobody to
 * escalate to, and the field stays null.
 */
public record VoidInvoiceRequest(
        @NotBlank String reason,
        @Size(max = 26) String authorisedByUid
) {

    /** Back-office / internal callers that carry no manager approval. */
    public VoidInvoiceRequest(String reason) {
        this(reason, null);
    }
}
