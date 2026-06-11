package com.erp.modules.documents.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company DOC-#### render numbers from the generic code_sequence table (ADR-0023 D-8).
 * entity_kind = DOCUMENT, lazy — no seed row (first use creates it with next_value = 1).
 * Joins the caller's TX (REQUIRED). The uq_generated_document_company_number constraint backstops bugs.
 */
@Component
public class DocumentNumberGenerator {

    private static final String KIND = "DOCUMENT";

    private final CodeSequenceRepository sequences;

    public DocumentNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next DOC-#### for the given company. Must be called inside an active write TX. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        long value = seq.consumeNext();
        return "DOC-" + String.format("%04d", value);
    }
}
