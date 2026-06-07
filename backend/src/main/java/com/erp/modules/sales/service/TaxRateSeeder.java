package com.erp.modules.sales.service;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.repository.TaxRateRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default VAT rate set for a company (ADR-0008 D-5b, FR-SALES-10).
 * Called from {@link com.erp.platform.bootstrap.BootstrapRunner} after the bootstrap company
 * is created, and from any future CompanyService.create path — mirrors UnitOfMeasureSeeder.
 *
 * <p>Rates: STANDARD = 18% (TZ), ZERO_RATED = 0%, EXEMPT = 0%. Skips any vat_status that
 * already has a row (idempotent).
 */
@Component
public class TaxRateSeeder {

    private static final Logger log = LoggerFactory.getLogger(TaxRateSeeder.class);

    /** TZ standard VAT rate. Owner-maintainable via TAXRATE.MANAGE; never hard-coded in logic. */
    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.1800");
    private static final BigDecimal ZERO_RATE     = BigDecimal.ZERO;

    private final TaxRateRepository taxRates;

    public TaxRateSeeder(TaxRateRepository taxRates) {
        this.taxRates = taxRates;
    }

    /**
     * Seeds the three default VAT rates for {@code companyId}. Skips any that already exist.
     * Runs in the caller's transaction (REQUIRED).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        seeded += seedIfAbsent(companyId, VatStatus.STANDARD,   STANDARD_RATE);
        seeded += seedIfAbsent(companyId, VatStatus.ZERO_RATED, ZERO_RATE);
        seeded += seedIfAbsent(companyId, VatStatus.EXEMPT,     ZERO_RATE);
        if (seeded > 0) {
            log.info("Seeded {} default tax rate(s) for company {}.", seeded, companyId);
        }
    }

    private int seedIfAbsent(Long companyId, VatStatus vatStatus, BigDecimal rate) {
        if (!taxRates.existsByCompanyIdAndVatStatus(companyId, vatStatus)) {
            taxRates.save(new TaxRate(companyId, vatStatus, rate, null));
            return 1;
        }
        return 0;
    }
}
