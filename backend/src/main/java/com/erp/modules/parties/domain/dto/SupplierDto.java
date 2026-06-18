package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/** Response DTO for a Supplier (ADR-0006 D-11). */
public record SupplierDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        PartyType partyType,
        String displayName,
        String legalName,
        String tin,
        boolean vatRegistered,
        String vrn,
        String businessRegNo,
        String mobileMoneyNo,
        String phone,
        String email,
        String physicalAddress,
        String postalAddress,
        String region,
        String district,
        String country,
        SupplierKind supplierKind,
        Integer paymentTermsDays,
        Long paymentTermsId,
        String defaultCurrency,
        Integer leadTimeDays,
        BigDecimal minOrderValue,
        Long defaultWhtTypeId,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static SupplierDto from(Supplier s) {
        return new SupplierDto(
                s.getId(),
                s.getUid(),
                s.getCompanyId(),
                s.getCode(),
                s.getPartyType(),
                s.getDisplayName(),
                s.getLegalName(),
                s.getTin(),
                s.isVatRegistered(),
                s.getVrn(),
                s.getBusinessRegNo(),
                s.getMobileMoneyNo(),
                s.getPhone(),
                s.getEmail(),
                s.getPhysicalAddress(),
                s.getPostalAddress(),
                s.getRegion(),
                s.getDistrict(),
                s.getCountry(),
                s.getSupplierKind(),
                s.getPaymentTermsDays(),
                s.getPaymentTermsId(),
                s.getDefaultCurrency() != null ? s.getDefaultCurrency().value() : null,
                s.getLeadTimeDays(),
                s.getMinOrderValue(),
                s.getDefaultWhtTypeId(),
                s.getStatus(),
                s.getVersion(),
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null,
                s.getCreatedBy(),
                s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null,
                s.getUpdatedBy()
        );
    }
}
