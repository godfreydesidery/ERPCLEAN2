package com.erp.modules.gl.service;

import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.service.CashBankAccountResolver;
import com.erp.modules.gl.domain.dto.GlPostingFailure;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.modules.sales.domain.dto.InvoicePostingTenderDto;
import com.erp.platform.common.money.FxDocumentConverter;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final GLPostingService     postingService;
    private final GLConfigResolver     configResolver;
    private final FxDocumentConverter  fxConverter;
    private final CompanyRepository    companies;
    /** ACC-05: resolves a tender's cash/bank account to its own GL link. Null in legacy unit tests. */
    private final CashBankAccountResolver cashBankAccounts;
    /** ACC-02: makes each swallowed failure durable and re-postable. Null only in unit fixtures. */
    private final GlPostingFailureRecorder failures;

    @Autowired
    public GLPostingSafeInvoker(GLPostingService    postingService,
                                GLConfigResolver    configResolver,
                                FxDocumentConverter fxConverter,
                                CompanyRepository   companies,
                                CashBankAccountResolver cashBankAccounts,
                                GlPostingFailureRecorder failures) {
        this.postingService   = postingService;
        this.configResolver   = configResolver;
        this.fxConverter      = fxConverter;
        this.companies        = companies;
        this.cashBankAccounts = cashBankAccounts;
        this.failures         = failures;
    }

    /** Unit-fixture form with tender resolution but no failure recording. */
    public GLPostingSafeInvoker(GLPostingService    postingService,
                                GLConfigResolver    configResolver,
                                FxDocumentConverter fxConverter,
                                CompanyRepository   companies,
                                CashBankAccountResolver cashBankAccounts) {
        this(postingService, configResolver, fxConverter, companies, cashBankAccounts, null);
    }

    /**
     * Constructor for callers that post no tender legs (every tender then uses CASH) and record
     * no failures — unit fixtures.
     */
    public GLPostingSafeInvoker(GLPostingService    postingService,
                                GLConfigResolver    configResolver,
                                FxDocumentConverter fxConverter,
                                CompanyRepository   companies) {
        this(postingService, configResolver, fxConverter, companies, null, null);
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
     *
     * <p>ADR-0033 D-4c: {@code projectId}/{@code projectTaskId} are threaded onto the CR Sales
     * Revenue leg so the project P&amp;L roll-up can include this revenue. Null = untagged (no
     * change for untagged invoices — NFR-PROJ-04).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleInNewTx(Long companyId, Long branchId, String invoiceUid,
                                           String currency, BigDecimal gross, BigDecimal net,
                                           BigDecimal vat, boolean cashSale, LocalDate postingDate,
                                           Long costCentreValueId, Long departmentValueId,
                                           Long projectId, Long projectTaskId) {
        try {
            // ── ADR-0036 D-3: convert face amounts to BASE before building LineDrafts ──────
            // The GL engine (GLPostingServiceImpl) is BYTE-UNTOUCHED; it only ever sees base
            // currency lines. validateLine BR-GL-06 passes by construction. (D-3)
            //
            // Resolution of base currency: read from company record once per TX.
            String baseCurrency = companies.findById(companyId)
                    .map(com.erp.modules.iam.domain.entity.Company::getBaseCurrency)
                    .orElse("TZS");
            return postSale(companyId, branchId, invoiceUid, currency, gross, net, vat, cashSale,
                    List.of(), postingDate, costCentreValueId, departmentValueId,
                    projectId, projectTaskId, baseCurrency);
        } catch (Exception ex) {
            log.warn("GLPostingSafeInvoker: sale GL post failed for company={} invoice={} — "
                            + "GL not configured or period closed. error={}",
                    companyId, invoiceUid, ex.getMessage());
            recordSaleFailure(companyId, branchId, invoiceUid, currency, gross, net, vat,
                    cashSale, List.of(), postingDate, costCentreValueId, departmentValueId,
                    projectId, projectTaskId, ex);
            return null;
        }
    }

    /**
     * ACC-02: records a swallowed sale posting with every argument of the tender-split poster, so
     * a re-post goes through {@link #postSaleWithTendersInNewTx} and produces exactly the journal
     * a live sale would have (one debit per tender account, residual to AR/CASH).
     */
    private void recordSaleFailure(Long companyId, Long branchId, String invoiceUid,
                                   String currency, BigDecimal gross, BigDecimal net,
                                   BigDecimal vat, boolean cashSale,
                                   List<InvoicePostingTenderDto> tenders, LocalDate postingDate,
                                   Long costCentreValueId, Long departmentValueId,
                                   Long projectId, Long projectTaskId, Exception ex) {
        if (failures == null) {
            return;
        }
        failures.record(GlPostingFailure.of(GlPostingFailureKind.SALE, companyId, branchId,
                        JournalSourceType.SALES, invoiceUid, null, postingDate)
                .amount(gross)
                .arg("currency", currency)
                .arg("gross", gross)
                .arg("net", net)
                .arg("vat", vat)
                .arg("cashSale", cashSale)
                .arg("tenders", tenders == null ? List.of() : tenders)
                .arg("costCentreValueId", costCentreValueId)
                .arg("departmentValueId", departmentValueId)
                .arg("projectId", projectId)
                .arg("projectTaskId", projectTaskId), ex);
    }

    /**
     * SAL-06 / ACC-04 / ACC-05: the sale posting with its counter tenders. Same entry as
     * {@link #postSaleInNewTx} except the single DR Cash/AR leg is split:
     * <ul>
     *   <li>each tender (amount − change) debits the GL account linked to the cash/bank account it
     *       landed in ({@code sales_invoice_payments.cash_bank_account_id}), or the company's
     *       {@code CASH} mapping when the tender named no account (or that account cannot take a
     *       posting) — grouped into one line per GL account;</li>
     *   <li>whatever the tenders did not cover debits AR on a credit sale (the AR open item is that
     *       same outstanding amount) or {@code CASH} otherwise, exactly as before.</li>
     * </ul>
     * With no tenders the entry is identical to {@link #postSaleInNewTx}. The POS till's own cash
     * account is deliberately NOT used as a fallback: POS payouts and session variances post to
     * {@code CASH}, so moving the drawer's sales elsewhere would split one drawer across two GL
     * accounts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleWithTendersInNewTx(Long companyId, Long branchId,
                                                      String invoiceUid, String currency,
                                                      BigDecimal gross, BigDecimal net,
                                                      BigDecimal vat, boolean cashSale,
                                                      List<InvoicePostingTenderDto> tenders,
                                                      LocalDate postingDate,
                                                      Long costCentreValueId,
                                                      Long departmentValueId,
                                                      Long projectId, Long projectTaskId) {
        try {
            String baseCurrency = companies.findScopedById(companyId)
                    .map(com.erp.modules.iam.domain.entity.Company::getBaseCurrency)
                    .orElse("TZS");
            return postSale(companyId, branchId, invoiceUid, currency, gross, net, vat, cashSale,
                    tenders == null ? List.of() : tenders, postingDate, costCentreValueId,
                    departmentValueId, projectId, projectTaskId, baseCurrency);
        } catch (Exception ex) {
            log.warn("GLPostingSafeInvoker: sale GL post failed for company={} invoice={} — "
                            + "GL not configured or period closed. error={}",
                    companyId, invoiceUid, ex.getMessage());
            recordSaleFailure(companyId, branchId, invoiceUid, currency, gross, net, vat,
                    cashSale, tenders, postingDate, costCentreValueId, departmentValueId,
                    projectId, projectTaskId, ex);
            return null;
        }
    }

    /** Builds and posts the sale entry (shared by both sale entry points; runs in their TX). */
    private JournalEntryDto postSale(Long companyId, Long branchId, String invoiceUid,
                                     String currency, BigDecimal gross, BigDecimal net,
                                     BigDecimal vat, boolean cashSale,
                                     List<InvoicePostingTenderDto> tenders, LocalDate postingDate,
                                     Long costCentreValueId, Long departmentValueId,
                                     Long projectId, Long projectTaskId, String baseCurrency) {
        // Convert each leg independently HALF_UP, then compute ONE debit leg as the BALANCING
        // PLUG so Σbase == 0 exactly, absorbing any rounding residual. (D-3/D-8)
        ConvertedAmount netConv = fxConverter.toBase(net, currency, companyId, postingDate);
        String postCurrency = baseCurrency;  // every LineDraft carries base currency (D-3)
        int scale = netConv.baseAmount().scale();

        BigDecimal baseNet = netConv.baseAmount();
        BigDecimal baseVat = BigDecimal.ZERO;
        if (vat != null && vat.compareTo(BigDecimal.ZERO) > 0) {
            baseVat = fxConverter.toBase(vat, currency, companyId, postingDate).baseAmount();
        }

        ChartOfAccount cashAcct = configResolver.resolve(companyId, GlConfigKey.CASH);
        ChartOfAccount residualAcct = cashSale
                ? cashAcct
                : configResolver.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
        ChartOfAccount revenueAcct    = configResolver.resolve(companyId, GlConfigKey.SALES_REVENUE);
        ChartOfAccount vatPayableAcct = configResolver.resolve(companyId, GlConfigKey.VAT_PAYABLE);

        // ── Debit legs in FACE currency, one per GL account (insertion order kept) ──────────
        Map<Long, BigDecimal> tenderFace = new LinkedHashMap<>();
        BigDecimal tendered = BigDecimal.ZERO;
        for (InvoicePostingTenderDto t : tenders) {
            if (t == null || t.netAmount() == null || t.netAmount().signum() == 0) {
                continue;
            }
            tenderFace.merge(tenderGlAccountId(companyId, t, cashAcct), t.netAmount(),
                    BigDecimal::add);
            tendered = tendered.add(t.netAmount());
        }
        // What the tenders did not cover: AR on a credit sale (= the AR open item), else Cash.
        BigDecimal residualFace = gross.subtract(tendered);

        List<DebitLeg> legs = new ArrayList<>();
        DebitLeg residualLeg = null;
        for (Map.Entry<Long, BigDecimal> e : tenderFace.entrySet()) {
            if (e.getKey().equals(residualAcct.getId())) {
                continue; // folded into the residual leg below (e.g. a cash tender on a cash sale)
            }
            if (e.getValue().signum() != 0) {
                legs.add(new DebitLeg(e.getKey(), e.getValue(), "Sale takings"));
            }
        }
        BigDecimal residualTotal = residualFace.add(
                tenderFace.getOrDefault(residualAcct.getId(), BigDecimal.ZERO));
        if (residualTotal.signum() != 0 || legs.isEmpty()) {
            residualLeg = new DebitLeg(residualAcct.getId(), residualTotal,
                    legs.isEmpty() ? "Gross sale"
                            : (cashSale ? "Sale takings" : "Sale on account"));
            legs.add(residualLeg);
        }

        // ── Base amounts: every leg converted, one leg is the balancing plug ──────────────────
        // The plug is the single leg when there is only one (unchanged behaviour), else the
        // largest tender leg — so a split credit sale's AR leg is converted exactly like the AR
        // open item (outstanding × rate) and the subledger ties to the control account.
        DebitLeg plugLeg = null;
        for (DebitLeg leg : legs) {
            if (legs.size() > 1 && !cashSale && leg == residualLeg) {
                continue; // the AR leg of a split entry is converted, never plugged
            }
            if (plugLeg == null || leg.face().abs().compareTo(plugLeg.face().abs()) > 0) {
                plugLeg = leg;
            }
        }
        if (plugLeg == null) {
            plugLeg = legs.get(0);
        }
        List<BigDecimal> others = new ArrayList<>(List.of(baseNet.negate(), baseVat.negate()));
        Map<DebitLeg, BigDecimal> legBase = new LinkedHashMap<>();
        for (DebitLeg leg : legs) {
            if (leg == plugLeg) {
                continue;
            }
            BigDecimal b = fxConverter.toBase(leg.face(), currency, companyId, postingDate)
                    .baseAmount();
            legBase.put(leg, b);
            others.add(b);
        }
        legBase.put(plugLeg, fxConverter.balancingPlug(others, scale));

        List<LineDraft> lines = new ArrayList<>();
        // DR Cash/bank/AR legs — base currency, untagged (ADR-0025 D-6 / ADR-0036 D-3). A leg
        // that nets negative (change recorded above its own tender) posts as a credit, since a
        // journal line carries one side only.
        for (DebitLeg leg : legs) {
            BigDecimal b = legBase.get(leg);
            if (b.signum() > 0) {
                lines.add(new LineDraft(leg.glAccountId(), b, BigDecimal.ZERO,
                        postCurrency, leg.description()));
            } else if (b.signum() < 0) {
                lines.add(new LineDraft(leg.glAccountId(), BigDecimal.ZERO, b.negate(),
                        postCurrency, leg.description()));
            }
        }
        // CR Sales Revenue — base amount, carry dimension + project tag (ADR-0025 D-6 / ADR-0033 D-4c)
        lines.add(new LineDraft(revenueAcct.getId(), BigDecimal.ZERO, baseNet, postCurrency,
                "Sales revenue", costCentreValueId, departmentValueId, null, null,
                projectId, projectTaskId, null));
        if (vat != null && vat.compareTo(BigDecimal.ZERO) > 0) {
            // CR VAT Payable — base amount, untagged
            lines.add(new LineDraft(vatPayableAcct.getId(), BigDecimal.ZERO, baseVat,
                    postCurrency, "VAT payable"));
        }
        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate, "Sale " + invoiceUid,
                JournalSourceType.SALES, invoiceUid, null, null, lines);
        return postingService.post(draft);
    }

    /**
     * ACC-05: the GL account a tender debits — its cash/bank account's own GL link, else CASH.
     * Company-scoped through the resolver, so a foreign account id on a payment row never routes
     * money into another company's ledger.
     */
    private Long tenderGlAccountId(Long companyId, InvoicePostingTenderDto tender,
                                   ChartOfAccount cashAcct) {
        if (cashBankAccounts == null || tender.cashBankAccountId() == null) {
            return cashAcct.getId();
        }
        return cashBankAccounts.findSaleTenderGlAccount(companyId, tender.cashBankAccountId())
                .map(CashAccountGlResolutionDto::glAccountId)
                .orElseGet(() -> {
                    log.warn("GLPostingSafeInvoker: tender cash/bank account id={} cannot take a "
                                    + "posting in company={} — posting the tender to CASH instead",
                            tender.cashBankAccountId(), companyId);
                    return cashAcct.getId();
                });
    }

    /** One debit leg of a sale entry, in face currency (identity-keyed: legs never collide). */
    private static final class DebitLeg {
        private final Long glAccountId;
        private final BigDecimal face;
        private final String description;

        DebitLeg(Long glAccountId, BigDecimal face, String description) {
            this.glAccountId = glAccountId;
            this.face = face;
            this.description = description;
        }

        Long glAccountId() { return glAccountId; }
        BigDecimal face() { return face; }
        String description() { return description; }
    }

    /**
     * Backward-compatible overload for callers that supply dimension ids but not project ids.
     * Delegates to the full form with null project ids (NFR-PROJ-04 / NFR-CC-01).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleInNewTx(Long companyId, Long branchId, String invoiceUid,
                                           String currency, BigDecimal gross, BigDecimal net,
                                           BigDecimal vat, boolean cashSale, LocalDate postingDate,
                                           Long costCentreValueId, Long departmentValueId) {
        return postSaleInNewTx(companyId, branchId, invoiceUid, currency, gross, net, vat,
                               cashSale, postingDate, costCentreValueId, departmentValueId,
                               null, null);
    }

    /**
     * Backward-compatible overload for callers that do not supply dimension ids.
     * Delegates to the full form with null dimension and project ids (NFR-CC-01).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JournalEntryDto postSaleInNewTx(Long companyId, Long branchId, String invoiceUid,
                                           String currency, BigDecimal gross, BigDecimal net,
                                           BigDecimal vat, boolean cashSale, LocalDate postingDate) {
        return postSaleInNewTx(companyId, branchId, invoiceUid, currency, gross, net, vat,
                               cashSale, postingDate, null, null, null, null);
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
            if (failures != null) {
                BigDecimal total = draft.lines() == null ? null : draft.lines().stream()
                        .map(l -> l.debitAmount() != null ? l.debitAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                failures.record(GlPostingFailure.of(GlPostingFailureKind.JOURNAL_DRAFT,
                                draft.companyId(), draft.branchId(), draft.sourceType(),
                                draft.sourceRef(), draft.description(), draft.postingDate())
                        .amount(total)
                        .arg("draft", draft), ex);
            }
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
            recordReversalFailure(originalEntryUid, reversalDate, sourceType, sourceRef, postedBy, ex);
            return null;
        }
    }

    /**
     * ACC-02: the reversal signature carries no company; the recorder reads it from the original
     * entry in its own transaction (this one may be aborted). The branch is the request context's
     * — the outbox handlers install a system principal for the source document's branch.
     */
    private void recordReversalFailure(String originalEntryUid, LocalDate reversalDate,
                                       JournalSourceType sourceType, String sourceRef,
                                       Long postedBy, Exception ex) {
        if (failures == null) {
            return;
        }
        RequestContext.Principal ctx = RequestContext.get();
        failures.recordReversal(originalEntryUid, ctx != null ? ctx.companyId() : null,
                ctx != null ? ctx.branchId() : null,
                GlPostingFailure.of(GlPostingFailureKind.REVERSAL, null, null,
                                sourceType, sourceRef, null, reversalDate)
                        .arg("originalEntryUid", originalEntryUid)
                        .arg("postedBy", postedBy), ex);
    }
}
