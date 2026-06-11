package com.erp.modules.sales.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company document numbers for the Order-to-Cash spine (ADR-0021 D-12).
 *
 * <p>Reuses the generic {@code code_sequence} table (ADR-0007 D-6) under a
 * {@code SELECT … FOR UPDATE} row lock — identical to {@link SalesInvoiceCodeGenerator}.
 * entity_kind values: QUOTE (→ QUOTE-####), SALES_ORDER (→ SO-####),
 * DELIVERY (→ DEL-####), SALES_RETURN (→ RET-####).
 * Rows created lazily on first use (next_value = 1); no pre-seed required.
 */
@Component
public class OrderToCashNumberGenerator {

    private static final String KIND_QUOTE        = "QUOTE";
    private static final String KIND_SALES_ORDER  = "SALES_ORDER";
    private static final String KIND_DELIVERY     = "DELIVERY";
    private static final String KIND_SALES_RETURN = "SALES_RETURN";

    private final CodeSequenceRepository sequences;

    public OrderToCashNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next quotation number: {@code QUOTE-0001}. Called at SEND. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextQuote(Long companyId) {
        return format("QUOTE", allocate(companyId, KIND_QUOTE));
    }

    /** Allocates the next sales-order number: {@code SO-0001}. Called at create. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextSalesOrder(Long companyId) {
        return format("SO", allocate(companyId, KIND_SALES_ORDER));
    }

    /** Allocates the next delivery number: {@code DEL-0001}. Called at create. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextDelivery(Long companyId) {
        return format("DEL", allocate(companyId, KIND_DELIVERY));
    }

    /** Allocates the next sales-return number: {@code RET-0001}. Called at create. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextSalesReturn(Long companyId) {
        return format("RET", allocate(companyId, KIND_SALES_RETURN));
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
