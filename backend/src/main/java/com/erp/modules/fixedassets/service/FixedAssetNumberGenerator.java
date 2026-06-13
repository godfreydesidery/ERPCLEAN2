package com.erp.modules.fixedassets.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates FA-#### and DEPR-#### codes per company using the shared {@code code_sequence} table
 * (ADR-0030 D-10 / ADR-0007 D-6). Lazy-created on first use — no migration seeding required.
 */
@Component
public class FixedAssetNumberGenerator {

    private static final String KIND_FA   = "FIXED_ASSET";
    private static final String KIND_DEPR = "DEPRECIATION_RUN";

    private final CodeSequenceRepository sequences;

    public FixedAssetNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocate the next FA-#### code for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextAssetNumber(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_FA)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_FA)));
        return "FA-" + String.format("%04d", seq.consumeNext());
    }

    /** Allocate the next DEPR-#### code for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextRunNumber(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_DEPR)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_DEPR)));
        return "DEPR-" + String.format("%04d", seq.consumeNext());
    }
}
