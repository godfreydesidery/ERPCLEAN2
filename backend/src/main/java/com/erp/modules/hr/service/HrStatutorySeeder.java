package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.entity.PayeBand;
import com.erp.modules.hr.domain.entity.PayeBandSet;
import com.erp.modules.hr.domain.entity.StatutoryRateSet;
import com.erp.modules.hr.domain.enums.StatutoryRateType;
import com.erp.modules.hr.repository.PayeBandRepository;
import com.erp.modules.hr.repository.PayeBandSetRepository;
import com.erp.modules.hr.repository.StatutoryRateSetRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds TZ 2025/26 default statutory rates for a newly created company (ADR-0032 D-9).
 * The Flyway V60 migration does the same for companies that existed at migration time.
 * Both paths are idempotent. Called by BootstrapRunner and CompanyService.create.
 */
@Component
public class HrStatutorySeeder {

    private static final Logger log = LoggerFactory.getLogger(HrStatutorySeeder.class);

    private static final LocalDate TZ_2025 = LocalDate.of(2025, 7, 1);

    // TZ 2025/26 PAYE tax-free threshold
    private static final BigDecimal TAX_FREE_THRESHOLD = new BigDecimal("270000.0000");

    private final PayeBandSetRepository    payeBandSets;
    private final PayeBandRepository       payeBands;
    private final StatutoryRateSetRepository rateSets;

    public HrStatutorySeeder(PayeBandSetRepository payeBandSets,
                              PayeBandRepository payeBands,
                              StatutoryRateSetRepository rateSets) {
        this.payeBandSets = payeBandSets;
        this.payeBands    = payeBands;
        this.rateSets     = rateSets;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedPaye(companyId);
        seedRate(companyId, StatutoryRateType.NSSF,
                 new BigDecimal("10.00"), new BigDecimal("10.00"), "PENSIONABLE", null, null);
        seedRate(companyId, StatutoryRateType.WCF,
                 new BigDecimal("0.50"),  new BigDecimal("0.50"),  "GROSS",       null, null);
        seedRate(companyId, StatutoryRateType.SDL,
                 new BigDecimal("3.50"),  new BigDecimal("3.50"),  "GROSS",       null, (short) 10);
        seedRate(companyId, StatutoryRateType.HESLB,
                 new BigDecimal("15.00"), new BigDecimal("15.00"), "BASIC",       null, null);
    }

    private void seedPaye(Long companyId) {
        if (!payeBandSets.findInForce(companyId, TZ_2025).isEmpty()) {
            log.debug("HrStatutorySeeder: PAYE bands already present for company {}.", companyId);
            return;
        }
        PayeBandSet set = payeBandSets.save(
                new PayeBandSet(companyId, TZ_2025, TAX_FREE_THRESHOLD, "TZ PAYE 2025/26 (default)", null));
        Long setId = set.getId();

        // Band 1: 0 – 270,000  → 0%,  cumulative 0
        payeBands.save(new PayeBand(setId, companyId, (short) 1,
                BigDecimal.ZERO, new BigDecimal("0.0000"), BigDecimal.ZERO, null));
        // Band 2: 270,001 – 520,000 → 8%, cumulative 0
        payeBands.save(new PayeBand(setId, companyId, (short) 2,
                new BigDecimal("270001.0000"), new BigDecimal("8.0000"), BigDecimal.ZERO, null));
        // Band 3: 520,001 – 760,000 → 20%, cumulative 20,000
        payeBands.save(new PayeBand(setId, companyId, (short) 3,
                new BigDecimal("520001.0000"), new BigDecimal("20.0000"), new BigDecimal("20000.0000"), null));
        // Band 4: 760,001 – 1,000,000 → 25%, cumulative 68,000
        payeBands.save(new PayeBand(setId, companyId, (short) 4,
                new BigDecimal("760001.0000"), new BigDecimal("25.0000"), new BigDecimal("68000.0000"), null));
        // Band 5: 1,000,001+ → 30%, cumulative 128,000
        payeBands.save(new PayeBand(setId, companyId, (short) 5,
                new BigDecimal("1000001.0000"), new BigDecimal("30.0000"), new BigDecimal("128000.0000"), null));

        log.info("HrStatutorySeeder: seeded TZ PAYE 2025/26 bands for company {}.", companyId);
    }

    private void seedRate(Long companyId, StatutoryRateType type,
                           BigDecimal employeeRate, BigDecimal employerRate,
                           String basis, BigDecimal ceiling, Short headcountThreshold) {
        if (!rateSets.findInForce(companyId, type, TZ_2025).isEmpty()) {
            log.debug("HrStatutorySeeder: {} already present for company {}.", type, companyId);
            return;
        }
        rateSets.save(new StatutoryRateSet(companyId, type, TZ_2025,
                employeeRate, employerRate, basis,
                ceiling, headcountThreshold, null, null));
        log.info("HrStatutorySeeder: seeded {} for company {}.", type, companyId);
    }
}
