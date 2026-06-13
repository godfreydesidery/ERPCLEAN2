package com.erp.modules.manufacturing.service;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.manufacturing.domain.dto.WipReconciliationDto;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * WIP reconciliation query (ADR-0035 D-6, FR-MFG-14).
 *
 * <p>Compares two independent views of the WIP balance:
 * <ol>
 *   <li><b>Computed</b>: Σ(wip_debit_total − wip_credit_total) across open work orders
 *       (RELEASED/IN_PROGRESS/COMPLETED) from {@link WorkOrderRepository#sumOpenWip}.</li>
 *   <li><b>Expected (GL balance)</b>: net balance of the WIP_INVENTORY account from
 *       {@link JournalLineRepository#accountBalance}.</li>
 * </ol>
 * These two MUST tie at month-end (BR-MFG-05). A non-zero discrepancy surfaces a posting gap.
 */
@Component
public class WipReconQuery {

    private final WorkOrderRepository   workOrders;
    private final JournalLineRepository journalLines;
    private final GLConfigResolver      glResolver;

    public WipReconQuery(WorkOrderRepository workOrders,
                          JournalLineRepository journalLines,
                          GLConfigResolver glResolver) {
        this.workOrders   = workOrders;
        this.journalLines = journalLines;
        this.glResolver   = glResolver;
    }

    @Transactional(readOnly = true)
    public WipReconciliationDto reconcile(Long companyId) {
        BigDecimal computed = workOrders.sumOpenWip(companyId);
        if (computed == null) computed = BigDecimal.ZERO;

        Long wipAccountId = glResolver.resolve(companyId, GlConfigKey.WIP_INVENTORY).getId();
        BigDecimal glBalance = journalLines.accountBalance(companyId, wipAccountId);
        if (glBalance == null) glBalance = BigDecimal.ZERO;

        return WipReconciliationDto.of("WIP", computed, glBalance);
    }
}
