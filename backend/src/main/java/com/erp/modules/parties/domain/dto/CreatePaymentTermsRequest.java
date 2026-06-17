package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request DTO to create a PaymentTerms master (D-2). */
public record CreatePaymentTermsRequest(
        @NotNull Long companyId,
        @NotBlank String code,
        @NotBlank String name,
        @NotNull PaymentTermsBasis basis,
        @Min(0) int netDays,
        Integer discountDays,
        BigDecimal discountPercent
) {
}
