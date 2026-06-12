package com.erp.modules.projects.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company project numbers {@code PRJ-####} (ADR-0033 D-2, BR-PROJ-12).
 * Reuses the generic {@code code_sequence} table (ADR-0007 D-6) under SELECT ... FOR UPDATE.
 * The {@code entity_kind = PROJECT} row is created lazily on first use — no seed required.
 */
@Component
public class ProjectNumberGenerator {

    private static final String KIND = "PROJECT";

    private final CodeSequenceRepository sequences;

    public ProjectNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next project number: {@code PRJ-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        long value = seq.consumeNext();
        return "PRJ-" + String.format("%04d", value);
    }
}
