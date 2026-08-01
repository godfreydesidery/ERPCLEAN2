package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the per-company Sales Settings row with the entity/DB defaults — notably
 * {@code allow_negative_stock = false} (block a sale that would take on-hand negative).
 * Idempotent — skips if the company already has a row. Runs in the caller's transaction (REQUIRED),
 * like the other company seeders.
 */
@Service
public class SalesSettingsSeederImpl implements SalesSettingsSeeder {

    private static final Logger log = LoggerFactory.getLogger(SalesSettingsSeederImpl.class);

    private final SalesSettingsRepository settings;

    public SalesSettingsSeederImpl(SalesSettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        if (settings.findByCompanyId(companyId).isPresent()) {
            return;
        }
        // No setter calls: the entity's own field initialisers ARE the defaults (SO approval off,
        // backorder off, TZS) — re-stating them here would be a second place to keep in sync.
        settings.save(new SalesSettings(companyId, null));
        log.info("SalesSettingsSeeder: seeded default sales settings for company {}.", companyId);
    }
}
