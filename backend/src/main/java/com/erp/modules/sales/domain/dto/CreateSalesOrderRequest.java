package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateSalesOrderRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        String agentUid,
        @NotBlank String currency,
        @NotNull LocalDate orderDate,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        String notes,
        /** Optional: CRM opportunity that converted to this SO (ADR-0031 D-7, V52). Null for direct creates. */
        String sourceOpportunityUid,
        /** Optional: PaymentTerms master uid (ADR-0041 D1). Null = customer default at confirm. */
        String paymentTermsUid,
        /** Optional: customer SHIP_TO address uid to snapshot onto the order (ADR-0041 D2). */
        String shipToAddressUid,
        /** Optional: customer BILL_TO address uid to snapshot onto the order (ADR-0041 D2). */
        String billToAddressUid
) {
    /** Back-compat overload: omit paymentTerms / ship-to / bill-to → null. */
    public CreateSalesOrderRequest(String companyUid, String customerUid, String agentUid,
                                   String currency, LocalDate orderDate,
                                   BigDecimal docDiscountAmount, BigDecimal docDiscountPercent,
                                   String notes, String sourceOpportunityUid) {
        this(companyUid, customerUid, agentUid, currency, orderDate,
                docDiscountAmount, docDiscountPercent, notes, sourceOpportunityUid,
                null, null, null);
    }
}
