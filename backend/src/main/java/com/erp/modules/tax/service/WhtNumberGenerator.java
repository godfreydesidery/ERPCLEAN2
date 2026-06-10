package com.erp.modules.tax.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company WHT certificate numbers via code_sequence (ADR-0017 D-11).
 * Format: WHT-#### (row-locked, concurrency-safe — NFR-VAT-05).
 */
@Component
public class WhtNumberGenerator {

    private static final String KIND = "WHT";

    private final CodeSequenceRepository sequences;

    public WhtNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        return "WHT-" + String.format("%04d", seq.consumeNext());
    }
}
