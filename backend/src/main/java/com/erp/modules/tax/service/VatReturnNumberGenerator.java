package com.erp.modules.tax.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company VAT return numbers via code_sequence (ADR-0017 D-11).
 * Format: VATR-#### (row-locked, concurrency-safe — NFR-VAT-05).
 */
@Component
public class VatReturnNumberGenerator {

    private static final String KIND = "VAT_RETURN";

    private final CodeSequenceRepository sequences;

    public VatReturnNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        return "VATR-" + String.format("%04d", seq.consumeNext());
    }
}
