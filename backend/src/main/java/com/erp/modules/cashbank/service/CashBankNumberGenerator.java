package com.erp.modules.cashbank.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company Cash & Bank document numbers via code_sequence (ADR-0016 D-12).
 * Row-locked allocation guarantees concurrency safety (NFR-CASH-05).
 * Must be called inside a write transaction.
 */
@Component
public class CashBankNumberGenerator {

    private static final String KIND_ACCOUNT        = "CASH_BANK_ACCOUNT";
    private static final String KIND_TRANSFER        = "CASH_TRANSFER";
    private static final String KIND_TXN             = "CASH_TXN";
    private static final String KIND_RECONCILIATION  = "BANK_RECONCILIATION";

    private final CodeSequenceRepository sequences;

    public CashBankNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextAccount(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_ACCOUNT)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_ACCOUNT)));
        return "CB-" + String.format("%04d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextTransfer(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_TRANSFER)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_TRANSFER)));
        return "CBT-" + String.format("%04d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextTransaction(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_TXN)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_TXN)));
        return "CBTX-" + String.format("%04d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextReconciliation(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_RECONCILIATION)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_RECONCILIATION)));
        return "REC-" + String.format("%04d", seq.consumeNext());
    }
}
