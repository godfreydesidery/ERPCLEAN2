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
        /** Total VAT stated on the bill; 0 if none. */
        BigDecimal vatAmount,
        @NotBlank String currency,
        String tenderType,
        @NotEmpty @Valid List<BillLineRequest> lines
) {}
