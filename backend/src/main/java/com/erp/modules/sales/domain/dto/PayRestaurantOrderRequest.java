package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Takes payment for an order (ADR-0065 D-8): the order becomes an ordinary POS sale on
 * {@code sessionUid}, in one transaction. Fields mirror {@link PosSaleRequest} and mean the same.
 *
 * <p>{@code expectedTotal} is the total the customer was shown. If the server's price now differs
 * the payment is refused ({@code PRICE_CHANGED}) and nothing is posted — print the bill again.
 * Null compares against the last printed bill instead (no check when none was printed).
 *
 * <p>Optional order discount: {@code discountPercent} or {@code discountAmount}, applied as line
 * discounts under the company's discount ceiling; quote first and send the quoted gross.
 */
public record PayRestaurantOrderRequest(
        @NotBlank String sessionUid,
        Long customerId,
        @NotBlank @Size(max = 3) String currency,
        @Valid List<PosSaleRequest.PosTender> tenders,
        BigDecimal tenderedAmount,
        BigDecimal expectedTotal,
        Boolean ageVerified,
        Boolean belowCostApproved,
        @DecimalMin("0") @DecimalMax("100") BigDecimal discountPercent,
        @DecimalMin("0") BigDecimal discountAmount,
        @Size(max = 26) String discountAuthorisedByUid
) {

    /** Back-compat: a payment with no order discount. */
    public PayRestaurantOrderRequest(String sessionUid, Long customerId, String currency,
                                     List<PosSaleRequest.PosTender> tenders, BigDecimal tenderedAmount,
                                     BigDecimal expectedTotal, Boolean ageVerified,
                                     Boolean belowCostApproved) {
        this(sessionUid, customerId, currency, tenders, tenderedAmount, expectedTotal, ageVerified,
                belowCostApproved, null, null, null);
    }
}
