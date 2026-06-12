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
 * <p>Entity kinds:
 * <ul>
 *   <li>{@code PURCHASE_ORDER}       → {@code PO-####}</li>
 *   <li>{@code GOODS_RECEIPT}        → {@code GRN-####}</li>
 *   <li>{@code PURCHASE_REQUISITION} → {@code PR-####}   (ADR-0027)</li>
 *   <li>{@code RFQ}                  → {@code RFQ-####}  (ADR-0027)</li>
 *   <li>{@code SUPPLIER_QUOTE}       → {@code SQ-####}   (ADR-0027)</li>
 *   <li>{@code LANDED_COST}          → {@code LC-####}   (ADR-0027)</li>
 *   <li>{@code PURCHASE_RETURN}      → {@code PRET-####} (ADR-0027)</li>
 * </ul>
 *
 * <p>Uses {@code SELECT … FOR UPDATE} via {@link CodeSequenceRepository} to serialise concurrent
 * allocations for the same company+kind. Called ONLY inside an active write transaction (REQUIRED).
 * The {@code uq_*_company_number} DB constraints backstop any generator bug.
 */
@Component
public class PurchaseNumberGenerator {

    private static final String KIND_PO   = "PURCHASE_ORDER";
    private static final String KIND_GRN  = "GOODS_RECEIPT";
    // procurement-depth (ADR-0027)
    private static final String KIND_REQUISITION = "PURCHASE_REQUISITION";
    private static final String KIND_RFQ         = "RFQ";
    private static final String KIND_QUOTE        = "SUPPLIER_QUOTE";
    private static final String KIND_LANDED_COST  = "LANDED_COST";
    private static final String KIND_RETURN       = "PURCHASE_RETURN";

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

    // --- procurement-depth (ADR-0027) ---

    /** Allocates the next {@code PR-####} for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextRequisition(Long companyId) {
        return next(companyId, KIND_REQUISITION, "PR");
    }

    /** Allocates the next {@code RFQ-####} for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextRfq(Long companyId) {
        return next(companyId, KIND_RFQ, "RFQ");
    }

    /** Allocates the next {@code SQ-####} for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextSupplierQuote(Long companyId) {
        return next(companyId, KIND_QUOTE, "SQ");
    }

    /** Allocates the next {@code LC-####} for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextLandedCost(Long companyId) {
        return next(companyId, KIND_LANDED_COST, "LC");
    }

    /** Allocates the next {@code PRET-####} for the given company. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextPurchaseReturn(Long companyId) {
        return next(companyId, KIND_RETURN, "PRET");
    }

    private String next(Long companyId, String entityKind, String prefix) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, entityKind)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, entityKind)));
        long value = seq.consumeNext();
        return prefix + "-" + String.format("%04d", value);
    }
}
