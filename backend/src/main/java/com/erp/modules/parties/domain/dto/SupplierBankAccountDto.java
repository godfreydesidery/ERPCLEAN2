package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.SupplierBankAccount;
import com.erp.platform.common.domain.MasterStatus;

/** Response DTO for SupplierBankAccount (D-4, ADR-0040). Carries both id and uid. */
public record SupplierBankAccountDto(
        Long id,
        String uid,
        Long companyId,
        Long supplierId,
        String bankName,
        String accountName,
        String accountNo,
        String bankBranch,
        String iban,
        String swiftBic,
        String currency,
        boolean isDefault,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {
    public static SupplierBankAccountDto from(SupplierBankAccount sba) {
        return new SupplierBankAccountDto(
                sba.getId(),
                sba.getUid(),
                sba.getCompanyId(),
                sba.getSupplierId(),
                sba.getBankName(),
                sba.getAccountName(),
                sba.getAccountNo(),
                sba.getBankBranch(),
                sba.getIban(),
                sba.getSwiftBic(),
                sba.getCurrency() != null ? sba.getCurrency().value() : null,
                sba.isDefault(),
                sba.getStatus(),
                sba.getVersion(),
                sba.getCreatedAt() != null ? sba.getCreatedAt().toString() : null,
                sba.getCreatedBy(),
                sba.getUpdatedAt() != null ? sba.getUpdatedAt().toString() : null,
                sba.getUpdatedBy()
        );
    }
}
