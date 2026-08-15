package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateTenantRequest;
import com.erp.modules.iam.domain.dto.OrganisationDto;
import java.util.List;

/**
 * Organisation read access (DATA-MODEL §1.1). The organisation is created by bootstrap, not through
 * an admin endpoint, so this is read-only for now. Controllers depend on this interface (DIP).
 */
public interface OrganisationService {

    /** The deployment's single organisation. 404 if none exists yet (bootstrap not run). */
    OrganisationDto current();

    /** All organisations, name-ordered. One row in the single-org deployment model. */
    List<OrganisationDto> list();

    /**
     * Provisions a whole new tenant (ADR-0062 P5-2). Platform-operator only — {@code ORG.CREATE}
     * lives in the {@code platform} permission module, which V102's narrowed CROSS JOIN keeps out of
     * every tenant's ORG_ADMIN, so no customer can reach this however much authority they hold.
     */
    OrganisationDto createTenant(CreateTenantRequest request);

    /**
     * Suspends or resumes a tenant (P5-3). Enforced at login by
     * {@code AuthServiceImpl.assertTenantIsOpen}, which already refuses a non-ACTIVE organisation
     * with a message that says nothing about billing or suspension.
     */
    OrganisationDto setStatus(String uid, boolean active);
}
