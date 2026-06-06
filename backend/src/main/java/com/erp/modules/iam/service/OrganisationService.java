package com.erp.modules.iam.service;

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
}
