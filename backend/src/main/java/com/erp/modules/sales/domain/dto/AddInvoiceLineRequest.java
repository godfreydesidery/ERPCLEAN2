package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO to add a line to a draft sales invoice (ADR-0008 D-12, FR-SALES-04).
 * productUid and unitUid are scoped to the invoice's company (F15 pattern).
 * lineDiscountAmount and lineDiscountPercent are alternative forms — at most one should be set.
 * The positive-quantity rule is enforced in the service with a friendly message (a {@code @Positive}
 * annotation would leak the raw field name into the user-facing error).
 *
 * @param discountAuthorisedByUid optional (K7). The {@code authoriserUid} handed back by a
 *        successful manager step-up ({@code POST /api/v1/auth/verify-authority} with permission code
 *        {@code SALES.DISCOUNT.OVERRIDE}). Only consulted when the company's discount policy is
 *        APPROVE and the requested discount exceeds the configured ceiling; the server re-verifies
 *        that the named user is active and genuinely holds that permission in the invoice's company,
 *        so supplying a uid is not by itself an approval. Existing clients omit it and are
 *        unaffected — the policy defaults to OFF.
 * @param unitPriceOverride optional. The unit price the seller states for this line, used
 *        <em>only</em> when the catalogue cannot price it (no price list covers the product in this
 *        company). It is not a way to discount a listed product: when a list price exists it wins
 *        and this value is refused, because changing a listed price is a permissioned act and has
 *        its own endpoint ({@code POST .../lines/uid/{lineUid}/override-price}, gated by
 *        {@code SALES.INVOICE.OVERRIDE}). Without this field a company with no price list could not
 *        invoice anything at all.
 */
public record AddInvoiceLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        @Size(max = 26) String discountAuthorisedByUid,
        // @Positive, not @PositiveOrZero: a zero here would give the goods away, and because a zero
        // "stated price" also reads as equal to the resolved price, no override permission would be
        // demanded and no priceOverridden mark stamped — a plain cashier could invoice for free.
        // Mirrors the dedicated override endpoint, which refuses signum() <= 0.
        @Positive BigDecimal unitPriceOverride
) {

    /** Back-compat: add a line with no manager authorisation attached (the overwhelming case). */
    public AddInvoiceLineRequest(String productUid, String unitUid, BigDecimal quantity,
                                 BigDecimal lineDiscountAmount, BigDecimal lineDiscountPercent) {
        this(productUid, unitUid, quantity, lineDiscountAmount, lineDiscountPercent, null, null);
    }

    /** Back-compat: manager authorisation but no stated price (the pre-pricing-fix shape). */
    public AddInvoiceLineRequest(String productUid, String unitUid, BigDecimal quantity,
                                 BigDecimal lineDiscountAmount, BigDecimal lineDiscountPercent,
                                 String discountAuthorisedByUid) {
        this(productUid, unitUid, quantity, lineDiscountAmount, lineDiscountPercent,
                discountAuthorisedByUid, null);
    }
}
