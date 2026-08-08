package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.UnitPriceStatus;
import java.util.Optional;

/**
 * Non-throwing outcome of
 * {@link com.erp.modules.products.service.PriceResolutionService#findUnitListPriceQuote} — either a
 * resolved {@link UnitPriceQuoteDto}, or the reason no price could be expressed.
 *
 * <p><b>Why this exists.</b> The throwing sibling ({@code resolveUnitListPriceQuote}) signals
 * "no price row" / "unit not applicable" with {@code IllegalArgumentException} /
 * {@code IllegalStateException}. Both are thrown out of a {@code @Transactional} proxy, so by the
 * time a caller catches them Spring has already marked the surrounding transaction rollback-only:
 * the batch price read then returned a perfectly good list and the COMMIT blew up with
 * {@code UnexpectedRollbackException} (HTTP 500). Control flow by exception across a transaction
 * boundary was the defect — a batch caller must be able to ask "can this be priced?" without
 * touching the exception machinery at all.
 *
 * <p>{@link #quote()} is the {@code Optional} view for callers that only care whether a price came
 * back; {@link #status()} keeps the two documented "no" reasons distinguishable, which a bare
 * {@code Optional} return would have collapsed into one.
 *
 * @param status {@link UnitPriceStatus#RESOLVED} iff {@code price} is non-null
 * @param price  the resolved amount/currency/VAT stance, or {@code null} — prefer {@link #quote()}
 */
public record UnitPriceQuoteResult(UnitPriceStatus status, UnitPriceQuoteDto price) {

    /** A price was resolved. */
    public static UnitPriceQuoteResult resolved(UnitPriceQuoteDto price) {
        return new UnitPriceQuoteResult(UnitPriceStatus.RESOLVED, price);
    }

    /** No price could be expressed; {@code status} says why. */
    public static UnitPriceQuoteResult unpriced(UnitPriceStatus status) {
        return new UnitPriceQuoteResult(status, null);
    }

    /** The resolved quote, empty when {@link #status()} is not {@code RESOLVED}. */
    public Optional<UnitPriceQuoteDto> quote() {
        return Optional.ofNullable(price);
    }

    /** Convenience for callers that branch on success. */
    public boolean isResolved() {
        return status == UnitPriceStatus.RESOLVED;
    }
}
