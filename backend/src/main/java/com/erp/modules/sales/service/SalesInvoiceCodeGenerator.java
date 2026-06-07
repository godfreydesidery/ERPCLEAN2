package com.erp.modules.sales.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next per-company sales invoice number (ADR-0008 D-7, FR-SALES-23).
 *
 * <p>Reuses the generic {@code code_sequence} table (entity_kind = "SALES_INVOICE") under
 * a {@code SELECT … FOR UPDATE} row lock — the identical mechanism {@code ProductCodeGenerator}
 * uses (ADR-0007 D-6). No new numbering table. Formats as {@code INV-%04d} (zero-padded).
 *
 * <p>Called ONLY inside the finalise transaction (REQUIRED propagation — joins the caller's TX).
 * The {@code uq_sales_invoice_company_number} DB constraint backstops any generator bug.
 */
@Component
public class SalesInvoiceCodeGenerator {

    private final CodeSequenceRepository sequences;

    public SalesInvoiceCodeGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Allocates and returns the next invoice number for the given company.
     * Must be called within an active write transaction (joins via REQUIRED).
     *
     * @param companyId the owning company id
     * @return formatted number, e.g. {@code INV-0001}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, "SALES_INVOICE")
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, "SALES_INVOICE")));
        long value = seq.consumeNext();
        return "INV-" + String.format("%04d", value);
    }
}
