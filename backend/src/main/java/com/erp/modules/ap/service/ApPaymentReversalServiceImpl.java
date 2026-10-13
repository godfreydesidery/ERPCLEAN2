package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.service.CashTransactionRecorder;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.FiscalPeriodResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.AccountingSetupException;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP-03: reverse a posted supplier payment — the AP mirror of the customer-receipt reversal
 * (ARC-04). One transaction: a GL refusal (closed period, …) rolls the whole command back and
 * leaves the bills and the cash book untouched.
 */
@Service
@Transactional
public class ApPaymentReversalServiceImpl implements ApPaymentReversalService {

    private static final int MAX_REASON = 200;

    private final ApPaymentRepository payments;
    private final ApPaymentReversalSupport reversalSupport;
    private final ApPaymentService paymentService;
    private final GLPostingService glPosting;
    private final FiscalPeriodResolver fiscalPeriods;
    private final CashTransactionRecorder cashTxnRecorder;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final CompanyCalendar calendar;

    public ApPaymentReversalServiceImpl(ApPaymentRepository payments,
                                        ApPaymentReversalSupport reversalSupport,
                                        ApPaymentService paymentService,
                                        GLPostingService glPosting,
                                        FiscalPeriodResolver fiscalPeriods,
                                        CashTransactionRecorder cashTxnRecorder,
                                        ScopeGuard scopeGuard,
                                        AuditService audit,
                                        CompanyCalendar calendar) {
        this.payments        = payments;
        this.reversalSupport = reversalSupport;
        this.paymentService  = paymentService;
        this.glPosting       = glPosting;
        this.fiscalPeriods   = fiscalPeriods;
        this.cashTxnRecorder = cashTxnRecorder;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
        this.calendar        = calendar;
    }

    @Override
    public ApPaymentDto reverse(String paymentUid, String reason) {
        ApPayment payment = Lookups.orNotFound(payments.findByUid(paymentUid), "ApPayment", paymentUid);
        Long companyId = payment.getCompanyId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new IllegalArgumentException("Give a reason for reversing this payment.");
        }
        if (why.length() > MAX_REASON) {
            throw new IllegalArgumentException("Keep the reason to 200 characters or fewer.");
        }
        String number = payment.getPaymentNumber();
        if (payment.getReversedAt() != null) {
            throw new ConflictException("Payment " + number + " has already been reversed.");
        }
        if (payment.getGlEntryUid() == null) {
            throw new ConflictException("Payment " + number + " has no ledger entry, so it cannot"
                    + " be reversed here. Ask your accountant to correct it with a journal.");
        }

        // The payment's own period must still be open (an accountant's decision otherwise).
        try {
            fiscalPeriods.resolveOpen(companyId, payment.getPaymentDate());
        } catch (AccountingSetupException closed) {
            throw new ConflictException("Payment " + number + " is dated "
                    + payment.getPaymentDate().format(
                            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                    + ", in an accounting period that is closed, so it cannot be reversed."
                    + " Ask your accountant to reopen the period or post a correcting journal.");
        }

        // Withholding tax: the payment carries a WHT certificate the reversal cannot cancel.
        if (payment.getWhtAmount() != null && payment.getWhtAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new ConflictException("Payment " + number
                    + " had withholding tax deducted, so it cannot be reversed here."
                    + " Ask your accountant to correct it with a journal.");
        }

        // Dated today in the company's zone (never before the payment itself), like a
        // bounced-cheque reversal.
        LocalDate today = calendar.today(companyId);
        LocalDate reversalDate = today.isBefore(payment.getPaymentDate())
                ? payment.getPaymentDate() : today;

        // APPEND-ONLY reversal of the payment journal: DR Cash|Bank / CR AP control (and the FX leg
        // if any) — every leg of the original swapped, so debits equal credits by construction.
        JournalEntryDto reversal = glPosting.postReversal(
                payment.getGlEntryUid(), reversalDate, JournalSourceType.AP_PAYMENT,
                payment.getUid(), actorId(), "payment " + number + " reversed: " + why);

        // The cash book moves with the GL: the money comes back IN on the same account.
        cashTxnRecorder.recordSettlementReversal(
                companyId, payment.getUid(), CashTxnType.AP_PAYMENT, CashTxnDirection.OUT,
                reversal.uid(), reversalDate,
                "Reversal of payment " + number + " - " + why, actorId());

        payment = reversalSupport.restoreAndMarkReversed(payment, actorId());

        audit.record(AuditEvent.of(AuditActions.AP_PAYMENT_REVERSE, "ap_payments",
                        payment.getId(), payment.getUid())
                .detail(Map.of(
                        "paymentNumber", number,
                        "amount", payment.getAmount().toPlainString(),
                        "reversalEntryUid", reversal.uid(),
                        "reason", why)));

        return paymentService.getByUid(payment.getUid());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
