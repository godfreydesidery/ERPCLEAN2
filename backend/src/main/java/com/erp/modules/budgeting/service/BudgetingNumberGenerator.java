package com.erp.modules.budgeting.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company BUDGET-#### numbers via the shipped {@code code_sequence} row-locked
 * mechanism (ADR-0034 D-11, ADR-0007 D-6). Created lazily — no seed rows.
 *
 * <p>Must be called inside a write transaction (propagation = MANDATORY).
 */
@Component
public class BudgetingNumberGenerator {

    private static final String KIND_BUDGET = "BUDGET";

    private final CodeSequenceRepository sequences;

    public BudgetingNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextBudget(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND_BUDGET)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND_BUDGET)));
        return "BUDGET-" + String.format("%04d", seq.consumeNext());
    }
}
