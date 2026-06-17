package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.CustomerContact;
import com.erp.modules.parties.domain.entity.PartyContactBase;
import com.erp.modules.parties.domain.entity.SupplierContact;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;

/**
 * Response DTO for any party contact sub-record (ADR-0040 D-3).
 * Carries both {@code id} (Long, serialised as string by global Jackson config) and {@code uid}.
 */
public record PartyContactDto(
        Long id,
        String uid,
        Long companyId,
        Long ownerId,
        String contactPerson,
        String phone,
        String email,
        boolean isPrimary,
        MasterStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PartyContactDto from(CustomerContact c) {
        return new PartyContactDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getCustomerId(),
                c.getContactPerson(), c.getPhone(), c.getEmail(),
                c.isPrimary(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }

    public static PartyContactDto from(SupplierContact c) {
        return new PartyContactDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getSupplierId(),
                c.getContactPerson(), c.getPhone(), c.getEmail(),
                c.isPrimary(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
