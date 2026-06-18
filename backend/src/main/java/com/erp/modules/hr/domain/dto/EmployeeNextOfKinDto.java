package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.entity.EmployeeNextOfKin;
import java.time.Instant;

/**
 * Response DTO for an employee next-of-kin sub-record (ADR-0040 D-11).
 * Carries both {@code id} (Long, serialised as string by global Jackson config) and {@code uid}.
 */
public record EmployeeNextOfKinDto(
        Long id,
        String uid,
        Long companyId,
        Long employeeId,
        String name,
        String relationship,
        String phone,
        String email,
        boolean isPrimary,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static EmployeeNextOfKinDto from(EmployeeNextOfKin k) {
        return new EmployeeNextOfKinDto(
                k.getId(), k.getUid(), k.getCompanyId(), k.getEmployeeId(),
                k.getName(), k.getRelationship(), k.getPhone(), k.getEmail(),
                k.isPrimary(), k.getStatus(), k.getCreatedAt(), k.getUpdatedAt());
    }
}
