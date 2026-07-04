package com.erp.modules.purchases.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request body for {@code POST /uid/{uid}/convert} (D-3, ADR-0027 D-3).
 *
 * <p>A requisition carries no supplier of its own ({@code preferredSupplierId} /
 * {@code suggestedSupplierId} exist on the line/header but are never populated by the current
 * create flow) — so Convert must take the supplier(s) as input from the caller at the point of
 * conversion.
 *
 * <p>Validated in the service (conditional on {@code targetType}, not expressible as simple
 * bean-validation annotations): {@code RFQ} requires a non-empty {@code supplierUids}; {@code
 * PURCHASE_ORDER} requires a non-blank {@code supplierUid}.
 */
public record ConvertRequisitionRequest(
        /** PURCHASE_ORDER | RFQ. */
        @NotBlank String targetType,
        /** Suppliers to invite — required (non-empty) when targetType == RFQ. */
        List<String> supplierUids,
        /** Supplier to order from — required when targetType == PURCHASE_ORDER. */
        String supplierUid,
        /** Optional: PO currency override; defaults to the company's base currency. */
        String currency
) {}
