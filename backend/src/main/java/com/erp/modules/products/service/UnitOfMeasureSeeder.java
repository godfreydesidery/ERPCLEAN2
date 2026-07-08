package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import java.time.Instant;
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
 *
 * <p>Each default unit carries its correct physical {@link DimensionType} + decimal scale (ADR-0041
 * D5): weight units (KG/GRAM) are WEIGHT, volume units (LITRE/ML) are VOLUME. This matters for
 * weighed goods (ADR-0044 D-1b) — a sell-by-weight product must resolve to a WEIGHT unit. The V4
 * SQL seed (and older boots) created these with the {@code COUNT} DEFAULT, so
 * {@link #seedDefaults(Long)} also reconciles any pre-existing physical unit that is still at the
 * pristine COUNT default (never re-classifying one an admin has already customised).
 */
@Component
public class UnitOfMeasureSeeder {

    private static final Logger log = LoggerFactory.getLogger(UnitOfMeasureSeeder.class);

    /** A canonical default unit with its physical dimension + display scale. */
    record UnitSeed(String code, String name, DimensionType dimension, short decimals, boolean fractional) {}

    /** Canonical default units. Order matches V4 migration. */
    static final List<UnitSeed> DEFAULT_UNITS = List.of(
            new UnitSeed("PCS",    "Pieces",      DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("BOX",    "Box",         DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("CARTON", "Carton",      DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("CRATE",  "Crate",       DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("PACK",   "Pack",        DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("KG",     "Kilogram",    DimensionType.WEIGHT, (short) 3, true),
            new UnitSeed("GRAM",   "Gram",        DimensionType.WEIGHT, (short) 0, true),
            new UnitSeed("LITRE",  "Litre",       DimensionType.VOLUME, (short) 3, true),
            new UnitSeed("ML",     "Millilitre",  DimensionType.VOLUME, (short) 0, true),
            new UnitSeed("DOZEN",  "Dozen",       DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("PAIR",   "Pair",        DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("SET",    "Set",         DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("ROLL",   "Roll",        DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("BAG",    "Bag",         DimensionType.COUNT,  (short) 0, true),
            new UnitSeed("BOTTLE", "Bottle",      DimensionType.COUNT,  (short) 0, true)
    );

    private final UnitOfMeasureRepository units;

    public UnitOfMeasureSeeder(UnitOfMeasureRepository units) {
        this.units = units;
    }

    /**
     * Seeds the default unit set for {@code companyId}. Skips any code that already exists
     * (idempotent — safe to call on bootstrap + company-create), then reconciles the physical
     * (weight/volume) units that a prior boot may have created with the COUNT default. Runs in the
     * caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        for (UnitSeed s : DEFAULT_UNITS) {
            if (!units.existsByCompanyIdAndCode(companyId, s.code())) {
                UnitOfMeasure u = new UnitOfMeasure(companyId, s.code(), s.name(), null);
                u.setDimensionType(s.dimension());
                u.setDecimalPlaces(s.decimals());
                u.setFractional(s.fractional());
                units.save(u);
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Seeded {} default unit(s) for company {}.", seeded, companyId);
        }
        reconcilePhysicalDimensions(companyId);
    }

    /**
     * Upgrades any weight/volume default unit that is still at the pristine {@code COUNT} default
     * (as created by the V4 SQL seed / older boots) to its correct dimension + decimal scale, so
     * weighed goods (ADR-0044 D-1b) resolve to a WEIGHT unit. Conservative: only touches a unit that
     * is still COUNT — a unit an admin has already re-classified is left untouched, and fractional is
     * only widened, never restricted. Idempotent.
     */
    private void reconcilePhysicalDimensions(Long companyId) {
        int fixed = 0;
        for (UnitSeed s : DEFAULT_UNITS) {
            if (s.dimension() == DimensionType.COUNT) {
                continue;
            }
            UnitOfMeasure u = units.findByCompanyIdAndCode(companyId, s.code()).orElse(null);
            if (u == null || u.getDimensionType() != DimensionType.COUNT) {
                continue; // absent, or already customised by an admin — leave it alone
            }
            u.setDimensionType(s.dimension());
            u.setDecimalPlaces(s.decimals());
            if (!u.isFractional()) {
                u.setFractional(s.fractional());
            }
            u.setUpdatedAt(Instant.now());
            units.save(u);
            fixed++;
        }
        if (fixed > 0) {
            log.info("Reconciled {} physical unit dimension(s) for company {}.", fixed, companyId);
        }
    }
}
