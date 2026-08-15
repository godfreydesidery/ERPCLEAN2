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
        String status,
        /**
         * The tenant's alias — the {@code @alias} half of a composed username (ADR-0062 D-7, P6-2b).
         * Exposed so the user-creation form can render it as a fixed adornment and show the
         * administrator the name that will actually be stored. Null on an organisation that has none,
         * where usernames stay bare.
         */
        String alias) {

    public static OrganisationDto from(Organisation o) {
        return new OrganisationDto(
                o.getId(),
                o.getUid(),
                o.getName(),
                o.getLegalName(),
                o.getDefaultTimeZone(),
                o.getStatus().name(),
                o.getAlias());
    }
}
