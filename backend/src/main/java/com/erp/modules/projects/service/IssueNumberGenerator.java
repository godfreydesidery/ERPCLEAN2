package com.erp.modules.projects.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company project material issue numbers {@code PJI-####} (ADR-0033 D-5).
 * Lazy kind — created on first use; no seed required.
 */
@Component
public class IssueNumberGenerator {

    private static final String KIND = "PROJECT_ISSUE";

    private final CodeSequenceRepository sequences;

    public IssueNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next issue number: {@code PJI-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        long value = seq.consumeNext();
        return "PJI-" + String.format("%04d", value);
    }
}
