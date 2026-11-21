package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A POS item return, as the till prints its refund slip (ADR-0072). Ids serialise as JSON strings;
 * decimals as JSON numbers.
 *
 * <p>The refund slip is non-fiscal. {@code grossAmount} is the cash handed to the customer.
 */
public record PosSaleReturnDto(
        Long id,
        String uid,
        String returnNo,
        Long companyId,
        Long branchId,
        String sessionUid,
        String invoiceUid,
        String invoiceNumber,
        String reason,
        BigDecimal netAmount,
        BigDecimal discountAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency,
        /** Always {@code CASH}. */
        String refundTender,
        /** The REFUND payout row, for the till's payout list. */
        String payoutUid,
        /** Display name of the approving supervisor; null when the caller was the supervisor. */
        String authorisedBy,
        /** Display name of the user who rang the return (the cashier, for a reprint). */
        String createdByName,
        String createdAt,
        List<LineDto> lines
) {

    /** One returned line, with the sold line's snapshot so the slip needs no second lookup. */
    public record LineDto(
            Long id,
            String uid,
            short lineNo,
            String invoiceLineUid,
            Long productId,
            String productCode,
            String productName,
            String unitName,
            /** In the sold line's unit. */
            BigDecimal qtyReturned,
            BigDecimal netAmount,
            BigDecimal discountAmount,
            BigDecimal vatAmount,
            BigDecimal grossAmount
    ) {
    }
}
