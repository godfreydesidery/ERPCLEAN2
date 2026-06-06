package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.Organisation;

/**
 * Response shape for an organisation (DATA-MODEL §1.1). Lets the web resolve the deployment's single
 * organisation by name (and uid) instead of making an admin paste a raw uid. Carries both the
 * numeric {@code id} (serialised as a JSON string globally) and the {@code uid} (URL identifier).
 */
public record OrganisationDto(
        Long id,
        String uid,
        String name,
        String legalName,
        String defaultTimeZone,
        String status) {

    public static OrganisationDto from(Organisation o) {
        return new OrganisationDto(
                o.getId(),
                o.getUid(),
                o.getName(),
                o.getLegalName(),
                o.getDefaultTimeZone(),
                o.getStatus().name());
    }
}
