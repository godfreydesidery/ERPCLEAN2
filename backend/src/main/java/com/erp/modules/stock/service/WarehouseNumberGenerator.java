package com.erp.modules.stock.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates per-company document numbers for the Inventory Depth documents (ADR-0028 D-9).
 *
 * <p>Reuses the generic {@code code_sequence} table (ADR-0007 D-6) under a
 * {@code SELECT … FOR UPDATE} row lock — identical to {@link com.erp.modules.sales.service.OrderToCashNumberGenerator}.
 * entity_kind values: STOCK_TRANSFER (→ TRF-####), STOCK_COUNT (→ CNT-####).
 * Rows created lazily on first use (next_value = 1); no pre-seed required.
 */
@Component
public class WarehouseNumberGenerator {

    private static final String KIND_TRANSFER = "STOCK_TRANSFER";
    private static final String KIND_COUNT    = "STOCK_COUNT";
    private static final String KIND_VAN_RECON = "VAN_RECON";

    private final CodeSequenceRepository sequences;

    public WarehouseNumberGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /** Allocates the next transfer number: {@code TRF-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextTransfer(Long companyId) {
        return format("TRF", allocate(companyId, KIND_TRANSFER));
    }

    /** Allocates the next count number: {@code CNT-0001}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextCount(Long companyId) {
        return format("CNT", allocate(companyId, KIND_COUNT));
    }

    /** Allocates the next van-reconciliation number: {@code VR-0001} (ADR-0051 D-8). */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextVanRecon(Long companyId) {
        return format("VR", allocate(companyId, KIND_VAN_RECON));
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
