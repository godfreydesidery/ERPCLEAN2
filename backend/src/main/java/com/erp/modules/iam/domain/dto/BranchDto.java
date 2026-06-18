package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.enums.BranchType;

/** Response shape for a branch (DATA-MODEL §1.3). */
public record BranchDto(
        Long id,
        String uid,
        Long companyId,
        String companyUid,
        String code,
        String name,
        String timeZone,
        boolean isDefault,
        Long managerId,
        BranchType branchType,
        String status) {

    public static BranchDto from(Branch b) {
        return new BranchDto(
                b.getId(),
                b.getUid(),
                b.getCompany().getId(),
                b.getCompany().getUid(),
                b.getCode(),
                b.getName(),
                b.getTimeZone(),
                b.isDefault(),
                b.getManagerId(),
                b.getBranchType(),
                b.getStatus().name());
    }
}
