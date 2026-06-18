package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO to create a draft Purchase Order (ADR-0011 D-12).
 *
 * <p>companyUid in the body (convention: §4b / ADR-0007 D-12).
 * Branch comes from RequestContext (X-Branch-Uid / default branch) — NOT the body.
 * supplierUid resolved by the service (cross-tenant safe, F15).
 * lines may be empty at create (lines can be added later while DRAFT).
 */
public record CreatePurchaseOrderRequest(
        String companyUid,
        String supplierUid,
        String currency,
        String notes,
        LocalDate expectedDate,
        /** Optional: PaymentTerms master uid (ADR-0041 D1). Null = supplier default at place. */
        String paymentTermsUid,
        List<AddPurchaseOrderLineRequest> lines
) {
    /** Back-compat overload: omit paymentTermsUid → null. */
    public CreatePurchaseOrderRequest(String companyUid, String supplierUid, String currency,
                                      String notes, LocalDate expectedDate,
                                      List<AddPurchaseOrderLineRequest> lines) {
        this(companyUid, supplierUid, currency, notes, expectedDate, null, lines);
    }
}
