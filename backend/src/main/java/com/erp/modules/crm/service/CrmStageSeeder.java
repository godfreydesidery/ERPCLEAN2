package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.entity.PipelineStage;
import com.erp.modules.crm.repository.PipelineStageRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default pipeline stages for a new company (ADR-0031 D-4).
 * Called from BootstrapRunner after company creation (and from any future CompanyService.create).
 * The SQL backfill in V51 handles existing companies using #12-safe md5-bounded UIDs;
 * this seeder generates fresh ULIDs (safe — no #12 exposure).
 */
@Component
public class CrmStageSeeder {

    private static final Logger log = LoggerFactory.getLogger(CrmStageSeeder.class);

    private final PipelineStageRepository stages;

    public CrmStageSeeder(PipelineStageRepository stages) {
        this.stages = stages;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        seeded += seedIfAbsent(companyId, "QUALIFICATION",  (short) 1, new BigDecimal("10.00"));
        seeded += seedIfAbsent(companyId, "NEEDS_ANALYSIS", (short) 2, new BigDecimal("25.00"));
        seeded += seedIfAbsent(companyId, "PROPOSAL",       (short) 3, new BigDecimal("50.00"));
        seeded += seedIfAbsent(companyId, "NEGOTIATION",    (short) 4, new BigDecimal("75.00"));
        seeded += seedIfAbsent(companyId, "CLOSING",        (short) 5, new BigDecimal("90.00"));
        if (seeded > 0) {
            log.info("Seeded {} default pipeline stage(s) for company {}.", seeded, companyId);
        }
    }

    private int seedIfAbsent(Long companyId, String name, short order, BigDecimal probability) {
        if (!stages.existsByCompanyIdAndName(companyId, name)) {
            stages.save(new PipelineStage(companyId, name, order, probability, null));
            return 1;
        }
        return 0;
    }
}
