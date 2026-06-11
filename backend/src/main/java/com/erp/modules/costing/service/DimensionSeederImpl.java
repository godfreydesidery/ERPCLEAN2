package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the two built-in dimensions (COST_CENTRE, DEPARTMENT) for a new company (ADR-0025 D-9).
 * Idempotent — checks before insert (mirrors the V27 ON CONFLICT DO NOTHING seed for existing
 * companies). Called from CompanyService.create or BootstrapRunner.
 */
@Service
@Transactional
public class DimensionSeederImpl implements DimensionSeeder {

    private static final List<SlotSeed> BUILT_INS = List.of(
            new SlotSeed(DimensionSlot.COST_CENTRE, "COST_CENTRE", "Cost Centre"),
            new SlotSeed(DimensionSlot.DEPARTMENT,  "DEPARTMENT",  "Department")
    );

    private final DimensionRepository repo;

    public DimensionSeederImpl(DimensionRepository repo) {
        this.repo = repo;
    }

    @Override
    public void seedBuiltIns(Long companyId) {
        for (SlotSeed seed : BUILT_INS) {
            if (repo.findByCompanyIdAndSlot(companyId, seed.slot()).isEmpty()) {
                repo.save(new Dimension(
                        companyId, seed.slot(), seed.code(), seed.name(), true, null));
            }
        }
    }

    private record SlotSeed(DimensionSlot slot, String code, String name) {}
}
