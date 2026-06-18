package com.erp.modules.ap.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EnterBillRequest(
        @NotBlank String companyUid,
        @NotBlank String supplierUid,
        @NotBlank String supplierInvoiceNo,
        String purchaseOrderUid,
        @NotNull LocalDate billDate,
        /** Nullable — if null the service derives from the supplier's PaymentTerms master or paymentTermsDays (D-2). */
        LocalDate dueDate,
        /**
         * Optional uid of a PaymentTerms master to apply to this bill (ADR-0041 D1).
         * Null = fall back to the supplier's default payment_terms_id.
         */
        String paymentTermsUid,
        /** Total VAT stated on the bill; 0 if none. */
        BigDecimal vatAmount,
        @NotBlank String currency,
        String tenderType,
        @NotEmpty @Valid List<BillLineRequest> lines
) {
    /** Back-compat overload: omit paymentTermsUid → null. */
    public EnterBillRequest(String companyUid, String supplierUid, String supplierInvoiceNo,
                            String purchaseOrderUid, LocalDate billDate, LocalDate dueDate,
                            BigDecimal vatAmount, String currency, String tenderType,
                            List<BillLineRequest> lines) {
        this(companyUid, supplierUid, supplierInvoiceNo, purchaseOrderUid, billDate, dueDate,
                null, vatAmount, currency, tenderType, lines);
    }
}
