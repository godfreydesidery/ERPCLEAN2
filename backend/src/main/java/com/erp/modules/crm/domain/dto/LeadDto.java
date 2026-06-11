package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.enums.LeadSource;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;

public record LeadDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String leadNumber,
        LeadStatus leadStatus,
        LeadSource leadSource,
        String displayName,
        String companyName,
        String contactPerson,
        String phone,
        String email,
        Long ownerUserId,
        Long customerId,
        String customerUid,
        String disqualifyReason,
        String notes,
        Instant qualifiedAt,
        Instant convertedAt,
        Instant disqualifiedAt,
        MasterStatus status,
        Long version,
        Instant createdAt,
        Long createdBy,
        Instant updatedAt,
        Long updatedBy
) {
    public static LeadDto from(Lead l) {
        return new LeadDto(
                l.getId(), l.getUid(), l.getCompanyId(), l.getBranchId(),
                l.getLeadNumber(), l.getLeadStatus(), l.getLeadSource(),
                l.getDisplayName(), l.getCompanyName(), l.getContactPerson(),
                l.getPhone(), l.getEmail(), l.getOwnerUserId(),
                l.getCustomerId(), l.getCustomerUid(), l.getDisqualifyReason(),
                l.getNotes(), l.getQualifiedAt(), l.getConvertedAt(), l.getDisqualifiedAt(),
                l.getStatus(), l.getVersion(), l.getCreatedAt(), l.getCreatedBy(),
                l.getUpdatedAt(), l.getUpdatedBy());
    }
}
