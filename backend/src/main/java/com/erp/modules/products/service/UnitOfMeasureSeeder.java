package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the canonical default unit set for a company (brief §New company → seed defaults).
 * Called from {@link com.erp.platform.bootstrap.BootstrapRunner} after the bootstrap company
 * is created, and from any future CompanyService.create path, so every company gets the
 * default units without relying solely on the V4 one-off SQL seed.
 *
 * <p>Uses REQUIRES_NEW so it can be called from within an existing transaction (bootstrap)
 * or standalone. Skips codes that already exist (idempotent).
 */
@Component
public class UnitOfMeasureSeeder {

    private static final Logger log = LoggerFactory.getLogger(UnitOfMeasureSeeder.class);

    /** Canonical default units: (code, name) pairs. Order matches V4 migration. */
    static final List<String[]> DEFAULT_UNITS = List.of(
            new String[]{"PCS",    "Pieces"},
            new String[]{"BOX",    "Box"},
            new String[]{"CARTON", "Carton"},
            new String[]{"CRATE",  "Crate"},
            new String[]{"PACK",   "Pack"},
            new String[]{"KG",     "Kilogram"},
            new String[]{"GRAM",   "Gram"},
            new String[]{"LITRE",  "Litre"},
            new String[]{"ML",     "Millilitre"},
            new String[]{"DOZEN",  "Dozen"},
            new String[]{"PAIR",   "Pair"},
            new String[]{"SET",    "Set"},
            new String[]{"ROLL",   "Roll"},
            new String[]{"BAG",    "Bag"},
            new String[]{"BOTTLE", "Bottle"}
    );

    private final UnitOfMeasureRepository units;

    public UnitOfMeasureSeeder(UnitOfMeasureRepository units) {
        this.units = units;
    }

    /**
     * Seeds the default unit set for {@code companyId}. Skips any code that already exists
     * (idempotent — safe to call on bootstrap + company-create). Runs in the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        for (String[] pair : DEFAULT_UNITS) {
            String code = pair[0];
            String name = pair[1];
            if (!units.existsByCompanyIdAndCode(companyId, code)) {
                units.save(new UnitOfMeasure(companyId, code, name, null));
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Seeded {} default unit(s) for company {}.", seeded, companyId);
        }
    }
}
