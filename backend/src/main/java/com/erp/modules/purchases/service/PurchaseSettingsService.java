package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.PurchaseSettingsDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseSettingsRequest;

/**
 * PO approval gate configuration (ADR-0027 D-6).
 * One settings record per company, created on first access.
 */
public interface PurchaseSettingsService {

    /** Returns existing settings or creates defaults for the company. */
    PurchaseSettingsDto getByCompanyUid(String companyUid);

    /** Upsert settings for the company (idempotent create + update). */
    PurchaseSettingsDto update(UpdatePurchaseSettingsRequest req);
}
