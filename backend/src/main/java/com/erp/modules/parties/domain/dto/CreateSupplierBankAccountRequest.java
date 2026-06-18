package com.erp.modules.parties.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to add a bank account to a supplier (D-4).
 * At least one of accountNo / iban must be non-null — validated at service layer (mirrors DB CHECK).
 */
public record CreateSupplierBankAccountRequest(
        @NotBlank String bankName,
        @NotBlank String accountName,
        String accountNo,
        String bankBranch,
        String iban,
        String swiftBic,
        /** ISO-4217 currency code — nullable (null = multi-currency / unspecified). */
        String currency,
        boolean isDefault
) {
}
