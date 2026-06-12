package com.erp.modules.sales.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PosSessionServiceImpl implements PosSessionService {

    private final PosSessionRepository       sessions;
    private final PosTillRepository          tills;
    private final PosSessionPayoutRepository payouts;
    private final SalesInvoiceRepository     invoices;
    private final GLPostingSafeInvoker       glInvoker;
    private final GLConfigResolver           glConfig;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public PosSessionServiceImpl(PosSessionRepository sessions,
                                  PosTillRepository tills,
                                  PosSessionPayoutRepository payouts,
                                  SalesInvoiceRepository invoices,
                                  GLPostingSafeInvoker glInvoker,
                                  GLConfigResolver glConfig,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.sessions   = sessions;
        this.tills      = tills;
        this.payouts    = payouts;
        this.invoices   = invoices;
        this.glInvoker  = glInvoker;
        this.glConfig   = glConfig;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public PosSessionDto openSession(OpenSessionRequest req) {
        var till = tills.findByUid(req.tillUid())
                .orElseThrow(() -> NotFoundException.of("PosTill", req.tillUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), till.getCompanyId());

        // Enforce at-most-one-open constraint (mirrors DB partial unique index)
        if (sessions.findByPosTillIdAndStatus(till.getId(), PosSessionStatus.OPEN).isPresent()) {
            throw new ConflictException("Till " + req.tillUid() + " already has an OPEN session.");
        }

        var session = new PosSession(till.getCompanyId(), till.getBranchId(), till.getId(),
                actorId(), req.openingFloatAmount(), actorId());
        session = sessions.save(session);

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_OPEN, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("tillUid", req.tillUid())));
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public PosSessionDto getSessionByUid(String uid) {
        var session = requireSession(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PosSessionDto> listSessions(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return sessions.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public void recordPayout(String sessionUid, PosPayoutRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpen(session);

        var payout = new PosSessionPayout(session.getCompanyId(), session.getBranchId(),
                session.getId(), req.payoutType(), req.amount(), req.reason(), actorId());
        payouts.save(payout);

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_PAYOUT, "pos_session_payouts",
                payout.getId(), payout.getUid())
                .detail(Map.of("type", req.payoutType().name(), "amount", req.amount().toPlainString())));
    }

    @Override
    public PosSessionDto closeSession(String sessionUid, CloseSessionRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpen(session);

        // Compute expected cash: opening + cash-sales total + net payouts
        BigDecimal cashSalesTotal = computeCashSalesTotal(session);
        BigDecimal netPayouts     = payouts.netPayoutForSession(session.getId());
        BigDecimal expected       = session.getOpeningFloatAmount()
                .add(cashSalesTotal).add(netPayouts);

        session.setCountedCashAmount(req.countedCashAmount());
        session.setExpectedCashAmount(expected);
        session.setVarianceAmount(req.countedCashAmount().subtract(expected));
        session.setStatus(PosSessionStatus.CLOSED);
        session.setClosedAt(Instant.now());
        session.setNotes(req.notes());
        session.setUpdatedAt(Instant.now());
        session.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_CLOSE, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("variance", session.getVarianceAmount().toPlainString())));
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public XReadDto xRead(String sessionUid) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpen(session);

        BigDecimal cashSalesTotal = computeCashSalesTotal(session);
        BigDecimal netPayouts     = payouts.netPayoutForSession(session.getId());
        BigDecimal expected       = session.getOpeningFloatAmount().add(cashSalesTotal).add(netPayouts);
        long invoiceCount         = countPosInvoices(session);

        return new XReadDto(session.getUid(), session.getPosTillId(), session.getCashierId(),
                session.getOpenedAt().toString(), session.getOpeningFloatAmount(),
                cashSalesTotal, netPayouts, expected, invoiceCount);
    }

    @Override
    public ZReadDto reconcileSession(String sessionUid, ReconcileSessionRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        if (session.getStatus() != PosSessionStatus.CLOSED) {
            throw new ConflictException("Session must be CLOSED before reconciliation.");
        }

        // Post variance GL entry if non-zero
        BigDecimal variance = session.getVarianceAmount();
        Long journalId = null;
        if (variance != null && variance.compareTo(BigDecimal.ZERO) != 0) {
            var journalEntry = postVarianceGl(session, variance);
            if (journalEntry != null) {
                journalId = journalEntry.id();
            }
        }

        session.setVarianceJournalId(journalId);
        if (req.notes() != null) {
            session.setNotes(req.notes());
        }
        session.setStatus(PosSessionStatus.RECONCILED);
        session.setReconciledAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_RECONCILE, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("variance", variance == null ? "0" : variance.toPlainString())));

        long invoiceCount = countPosInvoices(session);
        return new ZReadDto(session.getUid(), session.getPosTillId(), session.getCashierId(),
                session.getOpenedAt().toString(),
                session.getClosedAt() == null ? null : session.getClosedAt().toString(),
                session.getReconciledAt().toString(),
                session.getOpeningFloatAmount(),
                computeCashSalesTotal(session),
                payouts.netPayoutForSession(session.getId()),
                session.getExpectedCashAmount(),
                session.getCountedCashAmount(),
                variance,
                journalId,
                invoiceCount);
    }

    // ---- helpers ---------------------------------------------------------------

    private com.erp.modules.gl.domain.dto.JournalEntryDto postVarianceGl(PosSession session,
                                                                           BigDecimal variance) {
        // positive variance = cash over → CR POS_CASH_OVER (income), DR Cash
        // negative variance = cash short → DR POS_CASH_SHORT (expense), CR Cash
        try {
            var cashAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.CASH);
            String currency = "USD"; // fallback; should come from company default
            BigDecimal abs = variance.abs();
            LineDraft debitLine;
            LineDraft creditLine;
            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                // over: DR Cash, CR POS_CASH_OVER
                var overAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.POS_CASH_OVER);
                debitLine  = new LineDraft(cashAcct.getId(), abs, BigDecimal.ZERO, currency, "POS over");
                creditLine = new LineDraft(overAcct.getId(), BigDecimal.ZERO, abs, currency, "POS cash over");
            } else {
                // short: DR POS_CASH_SHORT, CR Cash
                var shortAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.POS_CASH_SHORT);
                debitLine  = new LineDraft(shortAcct.getId(), abs, BigDecimal.ZERO, currency, "POS cash short");
                creditLine = new LineDraft(cashAcct.getId(), BigDecimal.ZERO, abs, currency, "POS short");
            }
            var draft = new JournalEntryDraft(
                    session.getCompanyId(), session.getBranchId(), LocalDate.now(),
                    "POS session variance " + session.getUid(),
                    JournalSourceType.POS_VARIANCE, session.getUid(),
                    null, actorId(), List.of(debitLine, creditLine));
            return glInvoker.postInNewTx(draft);
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal computeCashSalesTotal(PosSession session) {
        return invoices.sumGrossByPosSession(session.getId());
    }

    private long countPosInvoices(PosSession session) {
        return invoices.countByPosSession(session.getId());
    }

    private void requireOpen(PosSession session) {
        if (session.getStatus() != PosSessionStatus.OPEN) {
            throw new ConflictException("Session " + session.getUid() + " is not OPEN.");
        }
    }

    private PosSession requireSession(String uid) {
        return sessions.findByUid(uid).orElseThrow(() -> NotFoundException.of("PosSession", uid));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    private PosSessionDto toDto(PosSession s) {
        return new PosSessionDto(s.getId(), s.getUid(), s.getCompanyId(), s.getBranchId(),
                s.getPosTillId(), s.getCashierId(), s.getStatus(),
                s.getOpenedAt() == null ? null : s.getOpenedAt().toString(),
                s.getClosedAt() == null ? null : s.getClosedAt().toString(),
                s.getReconciledAt() == null ? null : s.getReconciledAt().toString(),
                s.getOpeningFloatAmount(), s.getCountedCashAmount(),
                s.getExpectedCashAmount(), s.getVarianceAmount(),
                s.getVarianceJournalId(), s.getNotes());
    }
}
