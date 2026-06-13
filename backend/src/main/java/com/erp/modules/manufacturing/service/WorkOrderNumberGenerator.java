package com.erp.modules.manufacturing.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company work-order numbers via code_sequence (ADR-0035 D-12).
 * Row-locked allocation — concurrency-safe (NFR-MFG-05).
 * Lazy: the row is created on first use with next_value = 1.
 * Format: WO-#### (WO-0001, WO-0002, …)
 */
@Component
public class WorkOrderNumberGenerator {

    private static final String KIND = "WORK_ORDER";

    private final CodeSequenceRepository sequences;

    public WorkOrderNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        return "WO-" + String.format("%04d", seq.consumeNext());
    }
}
