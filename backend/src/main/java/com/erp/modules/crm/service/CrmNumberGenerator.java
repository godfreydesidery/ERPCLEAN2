package com.erp.modules.crm.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-company document number generator for the CRM module (ADR-0031 D-8).
 * Reuses code_sequence row-locked allocation (ADR-0007 D-6).
 * Kinds: LEAD (LEAD-####), OPPORTUNITY (OPP-####), ACTIVITY (ACT-####).
 * Rows created lazily on first use — no pre-seed.
 */
@Component
public class CrmNumberGenerator {

    private static final String KIND_LEAD        = "LEAD";
    private static final String KIND_OPPORTUNITY  = "OPPORTUNITY";
    private static final String KIND_ACTIVITY     = "ACTIVITY";

    private final CodeSequenceRepository sequences;

    public CrmNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String nextLead(Long companyId) {
        return "LEAD-" + String.format("%04d", allocate(companyId, KIND_LEAD));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String nextOpportunity(Long companyId) {
        return "OPP-" + String.format("%04d", allocate(companyId, KIND_OPPORTUNITY));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String nextActivity(Long companyId) {
        return "ACT-" + String.format("%04d", allocate(companyId, KIND_ACTIVITY));
    }

    private long allocate(Long companyId, String kind) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, kind)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, kind)));
        return seq.consumeNext();
    }
}
