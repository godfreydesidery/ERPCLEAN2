package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.UserBranch;

/**
 * Response shape for a user's branch assignment (DATA-MODEL §1.9). Carries the assignment {@code uid}
 * (the URL identifier for remove/set-default) plus the branch identity and the default flag, so the
 * UI can render the user's branches and which one is the default.
 */
public record UserBranchDto(
        Long id,
        String uid,
        String userUid,
        String branchUid,
        String branchCode,
        String branchName,
        String companyUid,
        boolean isDefault,
        String assignedAt) {

    /** {@code userUid} isn't on the entity (it stores user_id) — the service passes it in. */
    public static UserBranchDto from(UserBranch ub, String userUid) {
        return new UserBranchDto(
                ub.getId(),
                ub.getUid(),
                userUid,
                ub.getBranch().getUid(),
                ub.getBranch().getCode(),
                ub.getBranch().getName(),
                ub.getBranch().getCompany().getUid(),
                ub.isDefault(),
                ub.getAssignedAt() != null ? ub.getAssignedAt().toString() : null);
    }
}
