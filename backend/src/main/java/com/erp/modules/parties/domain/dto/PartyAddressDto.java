package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.CustomerAddress;
import com.erp.modules.parties.domain.entity.SupplierAddress;
import com.erp.modules.parties.domain.enums.AddressRole;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;

/**
 * Response DTO for any party address sub-record (ADR-0040 D-3).
 * Carries both {@code id} (Long, serialised as string by global Jackson config) and {@code uid}.
 */
public record PartyAddressDto(
        Long id,
        String uid,
        Long companyId,
        Long ownerId,
        AddressRole addressRole,
        boolean isDefault,
        String country,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String postalCode,
        MasterStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PartyAddressDto from(CustomerAddress a) {
        return new PartyAddressDto(
                a.getId(), a.getUid(), a.getCompanyId(), a.getCustomerId(),
                a.getAddressRole(), a.isDefault(),
                a.getCountry(), a.getAddressLine1(), a.getAddressLine2(),
                a.getCity(), a.getRegion(), a.getPostalCode(),
                a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
    }

    public static PartyAddressDto from(SupplierAddress a) {
        return new PartyAddressDto(
                a.getId(), a.getUid(), a.getCompanyId(), a.getSupplierId(),
                a.getAddressRole(), a.isDefault(),
                a.getCountry(), a.getAddressLine1(), a.getAddressLine2(),
                a.getCity(), a.getRegion(), a.getPostalCode(),
                a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
