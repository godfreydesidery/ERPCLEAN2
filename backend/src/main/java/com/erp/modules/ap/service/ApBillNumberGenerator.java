package com.erp.modules.ap.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company AP document numbers via code_sequence (ADR-0015 D-12).
 * Row-locked allocation guarantees concurrency safety (NFR-AP-05).
 * Must be called inside a write transaction.
 */
@Component
public class ApBillNumberGenerator {

    private static final String KIND_BILL        = "AP_BILL";
    private static final String KIND_PAYMENT     = "AP_PAYMENT";
    private static final String KIND_DEBIT_NOTE  = "AP_DEBIT_NOTE";
    private static final String KIND_PAYMENT_RUN = "AP_PAYMENT_RUN";

    private final CodeSequenceRepository sequences;

    public ApBillNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextBill(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_BILL)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_BILL)));
        return "BILL-" + String.format("%04d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextPayment(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_PAYMENT)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_PAYMENT)));
        return "PAYRUN-" + String.format("%04d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextDebitNote(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_DEBIT_NOTE)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_DEBIT_NOTE)));
        return "DBN-" + String.format("%04d", seq.consumeNext());
    }

    /** Allocates the next payment-run number (ADR-0041 D3); distinct sequence from payments. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextPaymentRun(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_PAYMENT_RUN)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_PAYMENT_RUN)));
        return "RUN-" + String.format("%04d", seq.consumeNext());
    }
}
