package com.erp.modules.purchases.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company purchase document numbers from the generic {@code code_sequence} table
 * (ADR-0011 D-6, reusing the mechanism ADR-0007 D-6 / ADR-0008 D-7 shipped).
 *
 * <p>Two entity_kinds:
 * <ul>
 *   <li>{@code PURCHASE_ORDER} → {@code PO-####}, allocated at order-placement.</li>
 *   <li>{@code GOODS_RECEIPT}  → {@code GRN-####}, allocated at receive.</li>
 * </ul>
 *
 * <p>Uses {@code SELECT … FOR UPDATE} via {@link CodeSequenceRepository} to serialise concurrent
 * allocations for the same company+kind. Called ONLY inside an active write transaction (REQUIRED).
 * The {@code uq_*_company_number} DB constraints backstop any generator bug.
 */
@Component
public class PurchaseNumberGenerator {

    private static final String KIND_PO  = "PURCHASE_ORDER";
    private static final String KIND_GRN = "GOODS_RECEIPT";

    private final CodeSequenceRepository sequences;

    public PurchaseNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next {@code PO-####} for the given company. Joins the caller's TX (REQUIRED). */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextPurchaseOrder(Long companyId) {
        return next(companyId, KIND_PO, "PO");
    }

    /** Allocates the next {@code GRN-####} for the given company. Joins the caller's TX (REQUIRED). */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextGoodsReceipt(Long companyId) {
        return next(companyId, KIND_GRN, "GRN");
    }

    private String next(Long companyId, String entityKind, String prefix) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, entityKind)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, entityKind)));
        long value = seq.consumeNext();
        return prefix + "-" + String.format("%04d", value);
    }
}
