package com.erp.modules.ar.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Replace a receipt's allocation set (ARC-06, BR-AR-12): apply money held on account to invoices
 * raised after the receipt, or move it between invoices. The lines given become the receipt's whole
 * allocation set; whatever they do not cover stays on account. An empty list returns the full
 * receipt to on-account. Posts nothing to the GL.
 */
public record ReallocateReceiptRequest(
        @NotNull @Valid List<RecordReceiptRequest.AllocationLineRequest> allocations
) {}
