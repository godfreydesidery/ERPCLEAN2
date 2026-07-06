package com.erp.modules.sales.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company document numbers for sales-depth entities (ADR-0029).
 * Reuses the generic code_sequence table (ADR-0007 D-6) with SELECT…FOR UPDATE row lock.
 * entity_kind values: BLANKET_ORDER (→ BO-####), STANDING_ORDER (→ STD-####).
 */
@Component
public class SalesDepthNumberGenerator {

    private static final String KIND_BLANKET  = "BLANKET_ORDER";
    private static final String KIND_STANDING = "STANDING_ORDER";
    private static final String KIND_POS_SESSION = "POS_SESSION";
    private static final String KIND_POS_TILL = "POS_TILL";

    private final CodeSequenceRepository sequences;

    public SalesDepthNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next blanket order number: {@code BO-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextBlanket(Long companyId) {
        return format("BO", allocate(companyId, KIND_BLANKET));
    }

    /** Allocates the next standing order number: {@code STD-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextStanding(Long companyId) {
        return format("STD", allocate(companyId, KIND_STANDING));
    }

    /** Allocates the next POS session number: {@code POS-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextPosSession(Long companyId) {
        return format("POS", allocate(companyId, KIND_POS_SESSION));
    }

    /** Allocates the next POS till code: {@code TILL-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextPosTill(Long companyId) {
        return format("TILL", allocate(companyId, KIND_POS_TILL));
    }

    private long allocate(Long companyId, String kind) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, kind)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, kind)));
        return seq.consumeNext();
    }

    private static String format(String prefix, long value) {
        return prefix + "-" + String.format("%04d", value);
    }
}
