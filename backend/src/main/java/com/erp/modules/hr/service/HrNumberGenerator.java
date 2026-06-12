package com.erp.modules.hr.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company HR document numbers via code_sequence (ADR-0032 D-12).
 * Row-locked allocation guarantees concurrency safety. Must be called inside a write transaction.
 */
@Component
public class HrNumberGenerator {

    private static final String KIND_PAYROLL_RUN = "HR_PAYROLL_RUN";
    private static final String KIND_LOAN        = "HR_LOAN";

    private final CodeSequenceRepository sequences;

    public HrNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextPayrollRun(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_PAYROLL_RUN)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_PAYROLL_RUN)));
        return "PR-" + String.format("%05d", seq.consumeNext());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextLoan(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_LOAN)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_LOAN)));
        return "LN-" + String.format("%05d", seq.consumeNext());
    }
}
