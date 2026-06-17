package com.erp.modules.parties.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTO to update a supplier bank account (D-4). */
public record UpdateSupplierBankAccountRequest(
        @NotBlank String bankName,
        @NotBlank String accountName,
        String accountNo,
        String bankBranch,
        String iban,
        String swiftBic,
        String currency
) {
}
