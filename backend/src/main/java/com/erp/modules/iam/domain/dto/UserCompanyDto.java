package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.UserCompany;

/**
 * Response shape for an explicit user↔company membership (V77). Carries both the membership row
 * identity ({@code id}, {@code uid}) and the company identity ({@code companyUid},
 * {@code companyName}) so a caller can display the membership list without a second round-trip.
 * {@code userUid} is not on the entity (stored as {@code user_id}) — the service passes it in.
 */
public record UserCompanyDto(
        Long id,
        String uid,
        String userUid,
        String companyUid,
        String companyName,
        String assignedAt,
        String revokedAt) {

    /** Build a DTO from a persisted entity. {@code userUid} is resolved by the service. */
    public static UserCompanyDto from(UserCompany uc, String userUid) {
        return new UserCompanyDto(
                uc.getId(),
                uc.getUid(),
                userUid,
                uc.getCompany().getUid(),
                uc.getCompany().getName(),
                uc.getAssignedAt() != null ? uc.getAssignedAt().toString() : null,
                uc.getRevokedAt() != null ? uc.getRevokedAt().toString() : null);
    }
}
