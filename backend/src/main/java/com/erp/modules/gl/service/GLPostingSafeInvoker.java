package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW wrapper around GL posting operations for use by outbox event handlers.
 *
 * <p><strong>Why this exists:</strong> {@code SalesPostingHandler} runs inside
 * {@code dispatchOne}'s REQUIRES_NEW TX and its own {@code @Transactional(MANDATORY)} joins it.
 * If {@link GLPostingService#post} throws (missing gl_config, closed period, etc.), Spring's TX
 * interceptor marks the participating TX (dispatchOne's) as rollback-only — even though the caller
 * has a Java {@code catch} block — because the proxy fires the rollback-mark <em>before</em> the
 * caller's catch runs. This silently rolls back all other handlers in the same dispatch (Stock).
 *
 * <p><strong>Fix:</strong> both posting methods catch all exceptions internally, log the anomaly,
 * and return {@code null}. They never propagate, so no outer TX is ever marked rollback-only.
 * The caller (SalesPostingHandler / SaleVoidingHandler) checks for a null return and logs
 * accordingly.
 *
 * <p>The REQUIRES_NEW propagation is still correct: GL writes commit (or roll back) independently
 * of the Stock TX, giving the double-entry ledger its own commit boundary.
 */
@Component
public class GLPostingSafeInvoker {

    private static final Logger log = LoggerFactory.getLogger(GLPostingSafeInvoker.class);

    private final GLPostingService postingService;
    private final GLConfigResolver configResolver;

    public GLPostingSafeInvoker(GLPostingService postingService, GLConfigResolver configResolver) {
        this.postingService = postingService;
        this.configResolver = configResolver;
    }

    /**
     * Resolves the sale's posting accounts AND posts the balanced entry, entirely within ONE
     * new independent transaction. Account resolution ({@link GLConfigResolver}, MANDATORY) must
     * happen INSIDE this REQUIRES_NEW boundary — if it ran in the caller's (dispatchOne's) TX a
     * missing {@code gl_config} would mark that TX rollback-only and silently roll back the Stock
     * handler sharing the same dispatch. Returns the posted entry, or {@code null} on any GL
     * anomaly (unmapped config, closed period, …); never propagates.
     *
     * <p>Entry shape (ADR-0013): DR Cash/AR (gross) · CR Sales Revenue (net) · CR VAT Payable (vat,
     * omitted when zero per {@code chk_journal_line_one_side}).
     *
     * <p>ADR-0025 D-6: only the P&L-relevant revenue leg carries the dimension tag (the cash/AR
     * debit leg posts untagged — D-6 sub-decision). Both ids nullable (untagged when null).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleInNewTx(Long companyId, Long branchId, String invoiceUid,
                                           String currency, BigDecimal gross, BigDecimal net,
                                           BigDecimal vat, boolean cashSale, LocalDate postingDate,
                                           Long costCentreValueId, Long departmentValueId) {
        try {
            ChartOfAccount debitAcct = cashSale
                    ? configResolver.resolve(companyId, GlConfigKey.CASH)
                    : configResolver.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
            ChartOfAccount revenueAcct    = configResolver.resolve(companyId, GlConfigKey.SALES_REVENUE);
            ChartOfAccount vatPayableAcct = configResolver.resolve(companyId, GlConfigKey.VAT_PAYABLE);

            List<LineDraft> lines = new ArrayList<>();
            // DR Cash/AR — balance-sheet control leg, untagged (ADR-0025 D-6)
            lines.add(new LineDraft(debitAcct.getId(), gross, BigDecimal.ZERO, currency, "Gross sale"));
            // CR Sales Revenue — P&L revenue leg, carry dimension tag (ADR-0025 D-6)
            lines.add(new LineDraft(revenueAcct.getId(), BigDecimal.ZERO, net, currency,
                    "Sales revenue", costCentreValueId, departmentValueId, null, null));
            if (vat != null && vat.compareTo(BigDecimal.ZERO) > 0) {
                // CR VAT Payable — balance-sheet control leg, untagged
                lines.add(new LineDraft(vatPayableAcct.getId(), BigDecimal.ZERO, vat, currency, "VAT payable"));
            }
            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate, "Sale " + invoiceUid,
                    JournalSourceType.SALES, invoiceUid, null, null, lines);
            return postingService.post(draft);
        } catch (Exception ex) {
            log.warn("GLPostingSafeInvoker: sale GL post failed for company={} invoice={} — "
                            + "GL not configured or period closed. error={}",
                    companyId, invoiceUid, ex.getMessage());
            return null;
        }
    }

    /**
     * Backward-compatible overload for callers that do not supply dimension ids.
     * Delegates to the full form with null dimension ids (NFR-CC-01).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleInNewTx(Long companyId, Long branchId, String invoiceUid,
                                           String currency, BigDecimal gross, BigDecimal net,
                                           BigDecimal vat, boolean cashSale, LocalDate postingDate) {
        return postSaleInNewTx(companyId, branchId, invoiceUid, currency, gross, net, vat,
                               cashSale, postingDate, null, null);
    }

    /**
     * Posts the draft in a new independent transaction. Returns the posted entry DTO, or
     * {@code null} if GL infra is not configured for this company (missing gl_configs, no open
     * fiscal period, etc.). Never propagates — the caller's outer TX is never poisoned.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postInNewTx(JournalEntryDraft draft) {
        try {
            return postingService.post(draft);
        } catch (Exception ex) {
            log.warn("GLPostingSafeInvoker: GL post failed for company={} sourceRef={} — "
                            + "GL not configured or period closed. error={}",
                    draft.companyId(), draft.sourceRef(), ex.getMessage());
            return null;
        }
    }

    /**
     * Posts a reversing entry in a new independent transaction. Returns the reversal DTO, or
     * {@code null} on anomaly. Never propagates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postReversalInNewTx(String originalEntryUid, LocalDate reversalDate,
                                               JournalSourceType sourceType, String sourceRef,
                                               Long postedBy) {
        try {
            return postingService.postReversal(
                    originalEntryUid, reversalDate, sourceType, sourceRef, postedBy);
        } catch (Exception ex) {
            log.warn("GLPostingSafeInvoker: GL reversal failed for originalUid={} sourceRef={} — "
                            + "error={}", originalEntryUid, sourceRef, ex.getMessage());
            return null;
        }
    }
}
