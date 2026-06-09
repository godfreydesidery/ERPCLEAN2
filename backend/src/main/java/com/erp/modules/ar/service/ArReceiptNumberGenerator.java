package com.erp.modules.ar.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company AR receipt numbers (RCT-####) and credit-note numbers (CRN-####).
 * Reuses the generic code_sequence table (ADR-0014 D-12, ADR-0007 D-6).
 * Called within the recording transaction (MANDATORY — joins the caller's TX).
 */
@Component
public class ArReceiptNumberGenerator {

    private static final String KIND_RECEIPT     = "AR_RECEIPT";
    private static final String KIND_CREDIT_NOTE = "AR_CREDIT_NOTE";

    private final CodeSequenceRepository sequences;

    public ArReceiptNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Next RCT-#### for this company. Must be called inside a write transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextReceipt(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_RECEIPT)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_RECEIPT)));
        return "RCT-" + String.format("%04d", seq.consumeNext());
    }

    /** Next CRN-#### for this company. Must be called inside a write transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCreditNote(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_CREDIT_NOTE)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_CREDIT_NOTE)));
        return "CRN-" + String.format("%04d", seq.consumeNext());
    }
}
