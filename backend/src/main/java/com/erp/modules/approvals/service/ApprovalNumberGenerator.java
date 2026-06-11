package com.erp.modules.approvals.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company approval request numbers in the format {@code APPROVAL-####} (ADR-0022 D-13).
 *
 * <p>Reuses the generic {@code code_sequence} table (ADR-0007 D-6) under a
 * {@code SELECT … FOR UPDATE} row lock — identical to {@link com.erp.modules.sales.service.OrderToCashNumberGenerator}.
 * The {@code entity_kind = APPROVAL} row is created lazily on first use (next_value = 1); no pre-seed.
 */
@Component
public class ApprovalNumberGenerator {

    private static final String KIND = "APPROVAL";

    private final CodeSequenceRepository sequences;

    public ApprovalNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next request number: {@code APPROVAL-0001}. Called at submit. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, KIND)));
        long value = seq.consumeNext();
        return "APPROVAL-" + String.format("%04d", value);
    }
}
