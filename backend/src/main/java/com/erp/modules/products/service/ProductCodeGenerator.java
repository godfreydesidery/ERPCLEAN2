package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-assigned product code generator (ADR-0007 D-6).
 *
 * <p>Allocates the next per-(company, entityKind) sequence value under a
 * {@code SELECT ... FOR UPDATE} row lock, formats it as {@code PROD-%04d}, and returns it —
 * all inside the caller's transaction. The unique index {@code uq_product_company_code} is
 * the DB backstop against generator bugs.
 *
 * <p>Creates the {@code code_sequence} row on first use (via {@code saveAndFlush}) so the lock
 * upgrade path is safe: new row → re-lock not needed since saveAndFlush holds the lock for the
 * remainder of the TX.
 */
@Component
public class ProductCodeGenerator {

    private final CodeSequenceRepository sequences;

    public ProductCodeGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Allocates the next product code for the given company. Must be called within an active
     * write transaction (joins the caller's TX via {@code REQUIRED}).
     *
     * @param companyId the owning company id
     * @return formatted code, e.g. {@code PROD-0001}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, "PRODUCT")
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, "PRODUCT")));

        long value = seq.consumeNext();
        return "PROD-" + String.format("%04d", value);
    }
}
