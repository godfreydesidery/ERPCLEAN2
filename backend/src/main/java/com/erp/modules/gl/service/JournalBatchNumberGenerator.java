package com.erp.modules.gl.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next per-company journal batch number (ADR-0013 D-3, NFR-GL-05).
 *
 * <p>Reuses the generic {@code code_sequence} table (entity_kind = "JOURNAL_BATCH") under
 * a {@code SELECT … FOR UPDATE} row lock — the identical mechanism {@code SalesInvoiceCodeGenerator}
 * uses (ADR-0007 D-6, ADR-0008 D-7). No new numbering table. Formats as {@code JB-%04d}.
 *
 * <p>Must be called inside an active write transaction (MANDATORY propagation — joins the caller's TX).
 * The {@code uq_journal_batch_company_number} DB constraint backstops any generator bug.
 */
@Component
public class JournalBatchNumberGenerator {

    private static final String ENTITY_KIND = "JOURNAL_BATCH";

    private final CodeSequenceRepository sequences;

    public JournalBatchNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Allocates and returns the next batch number for the given company.
     * Must be called within the posting transaction (MANDATORY).
     *
     * @param companyId the owning company id
     * @return formatted number, e.g. {@code JB-0001}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, ENTITY_KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, ENTITY_KIND)));
        long value = seq.consumeNext();
        return "JB-" + String.format("%04d", value);
    }
}
