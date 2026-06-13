package com.erp.modules.gl.events;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.hr.domain.entity.PayrollLine;
import com.erp.modules.hr.domain.entity.PayrollLineItem;
import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.domain.event.PayrollFinalisedPayload;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.hr.repository.PayrollLineItemRepository;
import com.erp.modules.hr.repository.PayrollLineRepository;
import com.erp.modules.hr.repository.PayrollRunRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code PAYROLL.FINALISED} — posts a balanced GL journal for a payroll run
 * (ADR-0032 D-7, FR-HR-19).
 *
 * <p>Journal shape (balances by construction — Σ DR == Σ CR):
 * <ul>
 *   <li>DR Salary &amp; allowance expense — Σ EARNING payroll_line_items by account</li>
 *   <li>DR Employer Statutory Expense — Σ nssf_employer + wcf + sdl</li>
 *   <li>CR PAYE Payable</li>
 *   <li>CR NSSF Payable (employee + employer)</li>
 *   <li>CR WCF Payable</li>
 *   <li>CR SDL Payable</li>
 *   <li>CR HESLB Payable</li>
 *   <li>CR per DEDUCTION payroll_line_item — loan receivables + voluntary deduction payables,
 *       grouped by gl_account_id (ADR-0032 D-7 rows 305-306)</li>
 *   <li>CR Net Wages Payable (net total)</li>
 * </ul>
 *
 * Mirrors {@link SalesPostingHandler}: MANDATORY TX, idempotency via IdempotencyGuard,
 * system RequestContext, REQUIRES_NEW GL commit via {@link GLPostingSafeInvoker#postInNewTx}.
 */
@Component
public class PayrollPostingHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PayrollPostingHandler.class);

    static final String CONSUMER = "GL.PAYROLL_POST";

    private final IdempotencyGuard           guard;
    private final PayrollRunRepository       payrollRuns;
    private final PayrollLineRepository      payrollLines;
    private final PayrollLineItemRepository  payrollLineItems;
    private final GLPostingSafeInvoker       safeInvoker;
    private final GLConfigResolver           configResolver;
    private final ObjectMapper               objectMapper;

    public PayrollPostingHandler(IdempotencyGuard guard,
                                  PayrollRunRepository payrollRuns,
                                  PayrollLineRepository payrollLines,
                                  PayrollLineItemRepository payrollLineItems,
                                  GLPostingSafeInvoker safeInvoker,
                                  GLConfigResolver configResolver,
                                  ObjectMapper objectMapper) {
        this.guard            = guard;
        this.payrollRuns      = payrollRuns;
        this.payrollLines     = payrollLines;
        this.payrollLineItems = payrollLineItems;
        this.safeInvoker      = safeInvoker;
        this.configResolver   = configResolver;
        this.objectMapper     = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.PAYROLL_FINALISED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("PayrollPostingHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        PayrollFinalisedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            postPayrollEntry(event, payload);
        } catch (Exception ex) {
            log.warn("PayrollPostingHandler: GL posting failed for run uid={} company={} — "
                            + "anomaly recorded, marking event processed. error={} event uid={}",
                    payload.runUid(), event.getCompanyId(), ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private void postPayrollEntry(DomainEvent event, PayrollFinalisedPayload payload) {
        Long companyId = event.getCompanyId();

        Optional<PayrollRun> runOpt = payrollRuns.findByUid(payload.runUid());
        if (runOpt.isEmpty() || runOpt.get().getStatus() == PayrollRunStatus.REVERSED) {
            log.warn("PayrollPostingHandler: PAYROLL.FINALISED for run uid={} not found or reversed — "
                    + "skipping. event uid={}", payload.runUid(), event.getUid());
            return;
        }
        PayrollRun run = runOpt.get();

        // Aggregate line-level statutory and gross totals
        List<PayrollLine> lines = payrollLines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());
        BigDecimal grossTotal   = BigDecimal.ZERO;
        BigDecimal payeTotal    = BigDecimal.ZERO;
        BigDecimal nssfEmpTotal = BigDecimal.ZERO;
        BigDecimal nssfErTotal  = BigDecimal.ZERO;
        BigDecimal wcfTotal     = BigDecimal.ZERO;
        BigDecimal sdlTotal     = BigDecimal.ZERO;
        BigDecimal heslbTotal   = BigDecimal.ZERO;
        BigDecimal netTotal     = BigDecimal.ZERO;

        // Fix #1 & #2: group DEDUCTION line-item amounts by their gl_account_id for CR legs
        // (covers both loan receivables and voluntary deduction payables — ADR-0032 D-7)
        Map<Long, BigDecimal> deductionByAccount = new LinkedHashMap<>();

        for (PayrollLine line : lines) {
            grossTotal   = grossTotal.add(line.getGrossAmount());
            payeTotal    = payeTotal.add(line.getPayeAmount());
            nssfEmpTotal = nssfEmpTotal.add(line.getNssfEmployeeAmount());
            nssfErTotal  = nssfErTotal.add(line.getNssfEmployerAmount());
            wcfTotal     = wcfTotal.add(line.getWcfEmployerAmount());
            sdlTotal     = sdlTotal.add(line.getSdlEmployerAmount());
            heslbTotal   = heslbTotal.add(line.getHeslbAmount());
            netTotal     = netTotal.add(line.getNetAmount());

            // Collect DEDUCTION line items grouped by GL account
            List<PayrollLineItem> items =
                    payrollLineItems.findByPayrollLineIdOrderByItemKindAscLabelAsc(line.getId());
            for (PayrollLineItem item : items) {
                if ("DEDUCTION".equals(item.getItemKind()) && item.getGlAccountId() != null
                        && item.getAmount() != null
                        && item.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                    deductionByAccount.merge(item.getGlAccountId(), item.getAmount(), BigDecimal::add);
                }
            }
        }
        BigDecimal nssfTotal       = nssfEmpTotal.add(nssfErTotal);
        BigDecimal employerStatExp = nssfErTotal.add(wcfTotal).add(sdlTotal);
        String currency = "TZS";

        // Build journal draft (balanced by construction per ADR-0032 D-7)
        List<LineDraft> draftLines = new ArrayList<>();

        // DR Salary Expense (gross wages)
        if (grossTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.SALARY_EXPENSE),
                    grossTotal, BigDecimal.ZERO, currency, "Gross payroll " + run.getRunNumber()));
        }
        // DR Employer Statutory Expense (employer-side NSSF + WCF + SDL)
        if (employerStatExp.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.EMPLOYER_STATUTORY_EXPENSE),
                    employerStatExp, BigDecimal.ZERO, currency, "Employer statutory contributions"));
        }
        // CR PAYE Payable
        if (payeTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.PAYE_PAYABLE),
                    BigDecimal.ZERO, payeTotal, currency, "PAYE payable"));
        }
        // CR NSSF Payable (employee + employer)
        if (nssfTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.NSSF_PAYABLE),
                    BigDecimal.ZERO, nssfTotal, currency, "NSSF payable"));
        }
        // CR WCF Payable
        if (wcfTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.WCF_PAYABLE),
                    BigDecimal.ZERO, wcfTotal, currency, "WCF payable"));
        }
        // CR SDL Payable
        if (sdlTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.SDL_PAYABLE),
                    BigDecimal.ZERO, sdlTotal, currency, "SDL payable"));
        }
        // CR HESLB Payable
        if (heslbTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.HESLB_PAYABLE),
                    BigDecimal.ZERO, heslbTotal, currency, "HESLB payable"));
        }
        // Fix #1 & #2: CR DEDUCTION items (loan receivables + voluntary deduction payables)
        // grouped by gl_account_id — ADR-0032 D-7 rows 305-306
        for (Map.Entry<Long, BigDecimal> entry : deductionByAccount.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) > 0) {
                draftLines.add(new LineDraft(entry.getKey(),
                        BigDecimal.ZERO, entry.getValue(), currency, "Deduction payable/recovery"));
            }
        }
        // CR Net Wages Payable
        if (netTotal.compareTo(BigDecimal.ZERO) > 0) {
            draftLines.add(new LineDraft(resolveId(companyId, GlConfigKey.NET_WAGES_PAYABLE),
                    BigDecimal.ZERO, netTotal, currency, "Net wages payable"));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, run.getBranchId(), run.getPayDate(),
                "Payroll run " + run.getRunNumber(),
                JournalSourceType.PAYROLL, run.getUid(), null, null, draftLines);

        JournalEntryDto posted = safeInvoker.postInNewTx(draft);
        if (posted != null) {
            run.setGlEntryUid(posted.uid());
            payrollRuns.save(run);
            log.info("PayrollPostingHandler: GL journal {} posted for payroll run {} company={}.",
                    posted.uid(), run.getRunNumber(), companyId);
        } else {
            log.warn("PayrollPostingHandler: GL posting returned null for run uid={} — "
                    + "GL config missing or period closed.", payload.runUid());
        }
    }

    private Long resolveId(Long companyId, GlConfigKey key) {
        ChartOfAccount acct = configResolver.resolve(companyId, key);
        return acct.getId();
    }

    private PayrollFinalisedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PayrollFinalisedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PayrollFinalisedPayload: " + ex.getMessage(), ex);
        }
    }
}
