package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code POST /api/v1/pos/sales/uid/{invoiceUid}/returns} — return chosen items of a
 * finalised POS sale and refund them in cash from the till (ADR-0072).
 *
 * @param reason          why the goods came back; at least 3 characters, recorded on the return, the
 *                        refund payout and the audit row
 * @param authorisedByUid the uid a successful manager step-up returned ({@code POST
 *                        /api/v1/auth/verify-authority}); required unless the caller themselves hold
 *                        {@code SALES.INVOICE.VOID}. Re-verified server-side, never trusted.
 * @param lines           the sold lines being returned — each sold line at most once
 */
public record PosSaleReturnRequest(
        @NotBlank @Size(min = 3, max = 255) String reason,
        @Size(max = 26) String authorisedByUid,
        @NotEmpty @Valid List<LineDto> lines
) {

    /**
     * One returned line.
     *
     * @param invoiceLineUid the uid of the SOLD invoice line (from {@code GET
     *                       /sales-invoices/uid/{uid}/lines})
     * @param qty            how much to return, in the sold line's own unit; &gt; 0 and at most the
     *                       quantity not yet returned
     */
    public record LineDto(
            @NotBlank @Size(max = 26) String invoiceLineUid,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty
    ) {
    }
}
