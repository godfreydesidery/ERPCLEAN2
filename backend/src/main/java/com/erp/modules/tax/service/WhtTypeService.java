package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.UpdateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import java.util.List;

/**
 * CRUD over WHT rate/type master (ADR-0017 D-2d, FR-WHT-01/02/03).
 */
public interface WhtTypeService {

    /** Create a new WHT type for a company. Code must be unique per company. */
    WhtTypeDto create(CreateWhtTypeRequest req);

    /** Update name/ratePct/active on a WHT type. */
    WhtTypeDto update(String uid, UpdateWhtTypeRequest req);

    /** List all WHT types for a company (active + inactive). */
    List<WhtTypeDto> listByCompany(Long companyId);

    /** Get a single WHT type by uid. */
    WhtTypeDto getByUid(String uid);

    /** Deactivate (soft-disable) a WHT type. */
    WhtTypeDto deactivate(String uid);
}
