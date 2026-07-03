package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;

/**
 * SO approval gate configuration (deferred item D-4, extends PR #189's engine-derived approval
 * flow). One settings record per company, created on first access. Mirrors
 * {@code PurchaseSettingsService}.
 */
public interface SalesSettingsService {

    /** Returns existing settings or defaults (unsaved) for the company. */
    SalesSettingsDto getByCompanyUid(String companyUid);

    /** Upsert settings for the company (idempotent create + update). */
    SalesSettingsDto update(UpdateSalesSettingsRequest req);
}
