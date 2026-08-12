package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.sales.domain.dto.AuthorisedReadRequest;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosExpenseIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The manager-authorised X-read / Z-read path (owner decision, 2026-08-12).
 *
 * <p><b>What this feature is.</b> A shop owner took the X/Z reads off the cashier role; because
 * denied actions are hidden at the till, the rows disappeared and the cashier could not even ask. The
 * till now keeps the entries visible and asks a manager to approve the single press. The manager's
 * password goes only to the step-up endpoint, which returns a uid and never a token; that uid rides on
 * ONE report request and is re-resolved here from scratch. Nothing is issued, so nothing has to
 * expire: the next report needs a fresh approval.
 *
 * <p><b>Why every principal below is NON-root.</b> {@code PermissionResolver.hasPermission} returns
 * true for root before it looks at anything, so a root caller is served on the "holds VIEW" branch and
 * the approval logic is never reached. A suite written as root would pass with the authorisation code
 * deleted — it would prove nothing at all.
 */
class PosSessionAuthorisedReadTest {

    /** The company the SESSION belongs to — the only company an approval may be resolved in. */
    private static final Long SESSION_COMPANY = 1L;

    private static final String VIEW_PERMISSION     = "POS.SESSION.VIEW";
    private static final String APPROVAL_PERMISSION = "POS.SESSION.RECONCILE";

    /** A 26-char ULID-shaped uid for the approving manager. */
    private static final String MANAGER_UID = "01J0MANAGER0000000000000AA";
    /** The cashier's own uid — what a self-approval attempt would send. */
    private static final String CASHIER_UID = "01J0CASHIER0000000000000BB";

    private static final Long CASHIER_ID = 99L;

    private PosSessionRepository       sessions;
    private PosSessionPayoutRepository payouts;
    private SalesInvoiceRepository     invoices;
    private SalesInvoicePaymentRepository tenderPayments;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private PermissionResolver         permissionResolver;
    private StepUpAuthService          stepUpAuth;
    private PosSessionServiceImpl      service;

    @BeforeEach
    void setUp() {
        sessions       = mock(PosSessionRepository.class);
        payouts        = mock(PosSessionPayoutRepository.class);
        invoices       = mock(SalesInvoiceRepository.class);
        tenderPayments = mock(SalesInvoicePaymentRepository.class);
        scopeGuard     = mock(ScopeGuard.class);
        audit          = mock(AuditService.class);
        permissionResolver = mock(PermissionResolver.class);
        stepUpAuth     = mock(StepUpAuthService.class);

        // The label collaborators are stubbed but never asserted on here: this suite is about WHO may
        // open a report, not what the report says. Mockito's default empty answers give the reads no
        // names, which is exactly the "cannot be named" fallback path.
        service = new PosSessionServiceImpl(sessions, mock(PosTillRepository.class), payouts,
                mock(PosExpenseIdempotencyRepository.class), invoices, tenderPayments,
                mock(GLPostingSafeInvoker.class), mock(GLConfigResolver.class),
                mock(GLPostingService.class), mock(CompanyRepository.class),
                mock(BranchRepository.class), mock(UserLookupService.class), scopeGuard, audit,
                mock(SalesDepthNumberGenerator.class), mock(TillExpenseGlSeeder.class),
                permissionResolver, stepUpAuth);

        // The cashier at the till: a real, ordinary, NON-root operator.
        RequestContext.set(new RequestContext.Principal(
                CASHIER_ID, "asha.cashier", false, SESSION_COMPANY, 1L, null));
        stubFigures();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // 1. A holder needs nobody
    // -------------------------------------------------------------------------

    /**
     * The unchanged case. A supervisor who holds {@code POS.SESSION.VIEW} is the person a cashier
     * would have called over; demanding that they also produce a second manager would deadlock a shop
     * with one manager on shift. The approval field is not consulted at all.
     */
    @Test
    void xRead_holderIsServed_withNoApproverSupplied() {
        callerHoldsView();
        PosSession session = openSession();
        when(sessions.findByUid("S-HOLDER")).thenReturn(Optional.of(session));

        var report = service.xReadAuthorised("S-HOLDER", new AuthorisedReadRequest(null));

        assertThat(report.expectedCashAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        verify(stepUpAuth, never()).verifyAuthoriserUid(anyString(), anyString(), anyLong());
        assertThat(printedAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "SERVED")
                .containsEntry("viewer", "asha.cashier")
                .as("no second person was involved, and the record must say so rather than imply one")
                .containsEntry("authorisedBy", "");
    }

    /** A holder is served even if a body arrives with someone else's uid in it — it is ignored. */
    @Test
    void xRead_holderIsServed_andAStrayApproverUidIsIgnored() {
        callerHoldsView();
        when(sessions.findByUid("S-HOLDER2")).thenReturn(Optional.of(openSession()));

        service.xReadAuthorised("S-HOLDER2", new AuthorisedReadRequest(MANAGER_UID));

        verify(stepUpAuth, never()).verifyAuthoriserUid(anyString(), anyString(), anyLong());
    }

    // -------------------------------------------------------------------------
    // 2. A non-holder with a genuine manager approval is served
    // -------------------------------------------------------------------------

    @Test
    void xRead_nonHolderWithValidApprover_isServed() {
        callerLacksView();
        managerApproves();
        when(sessions.findByUid("S-APPROVED")).thenReturn(Optional.of(openSession()));

        var report = service.xReadAuthorised("S-APPROVED", new AuthorisedReadRequest(MANAGER_UID));

        assertThat(report.expectedCashAmount())
                .as("the approved report is the SAME report, not a reduced or different one")
                .isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(printedAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "SERVED")
                .containsEntry("viewer", "asha.cashier")
                .as("the approving manager is named on the record, by username and by uid")
                .containsEntry("authorisedBy", "juma.manager")
                .containsEntry("authorisedByUid", MANAGER_UID);
    }

    @Test
    void zRead_nonHolderWithValidApprover_isServed() {
        callerLacksView();
        managerApproves();
        when(sessions.findByUid("S-Z-APPROVED")).thenReturn(Optional.of(reconciledSession()));

        var report = service.zReadAuthorised("S-Z-APPROVED", new AuthorisedReadRequest(MANAGER_UID));

        assertThat(report.varianceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(printedAudit(AuditActions.POS_SESSION_ZREAD).detail())
                .containsEntry("outcome", "SERVED")
                .containsEntry("authorisedBy", "juma.manager");
    }

    /**
     * The authority asked for must be the one the owner chose: {@code POS.SESSION.RECONCILE}, which
     * BRANCH_MANAGER holds and CASHIER deliberately does not. Asking for anything a cashier already
     * has would make the whole prompt theatre.
     */
    @Test
    void theApprovalIsCheckedAgainstTheReconcileAuthority() {
        callerLacksView();
        managerApproves();
        when(sessions.findByUid("S-CODE")).thenReturn(Optional.of(openSession()));

        service.xReadAuthorised("S-CODE", new AuthorisedReadRequest(MANAGER_UID));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(stepUpAuth).verifyAuthoriserUid(eq(MANAGER_UID), code.capture(), any());
        assertThat(code.getValue()).isEqualTo(APPROVAL_PERMISSION);
    }

    /**
     * Standing tenant-isolation rule: scope comes from the LOADED entity, never from a caller
     * parameter. The caller here is signed in to a DIFFERENT company from the session, and the
     * approval must still be resolved in the session's company — otherwise a request could nominate a
     * company in which its chosen "approver" happens to be privileged.
     */
    @Test
    void theApprovalIsResolvedInTheLoadedSessionsCompany_neverTheCallers() {
        callerLacksView();
        managerApproves();
        RequestContext.set(new RequestContext.Principal(
                CASHIER_ID, "asha.cashier", false, 777L, 1L, null));
        when(sessions.findByUid("S-TENANT")).thenReturn(Optional.of(openSession()));

        service.xReadAuthorised("S-TENANT", new AuthorisedReadRequest(MANAGER_UID));

        ArgumentCaptor<Long> company = ArgumentCaptor.forClass(Long.class);
        verify(stepUpAuth).verifyAuthoriserUid(eq(MANAGER_UID), anyString(), company.capture());
        assertThat(company.getValue())
                .as("the company must come from the loaded session, not the caller's context")
                .isEqualTo(SESSION_COMPANY);
    }

    // -------------------------------------------------------------------------
    // 3. Refusals
    // -------------------------------------------------------------------------

    @Test
    void xRead_nonHolderWithNoApprover_isRefusedAndAudited() {
        callerLacksView();
        when(sessions.findByUid("S-NONE")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() ->
                service.xReadAuthorised("S-NONE", new AuthorisedReadRequest(null)))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(PosSessionAuthorisedReadTest::isShopFloorSafe);

        verify(stepUpAuth, never()).verifyAuthoriserUid(anyString(), anyString(), anyLong());
        assertThat(refusalAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "REFUSED_NO_APPROVAL")
                .containsEntry("viewer", "asha.cashier");
    }

    /** An absent body is the same thing as an absent approval — not an accidental way through. */
    @Test
    void xRead_nonHolderWithNoRequestBodyAtAll_isRefused() {
        callerLacksView();
        when(sessions.findByUid("S-NOBODY")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.xReadAuthorised("S-NOBODY", null))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * A uid a client invented resolves to nobody. The refusal says nothing about whether that uid
     * exists — a till is not a place to enumerate who works here — but the attempted uid IS on the
     * audit row, which is what an investigation needs.
     */
    @Test
    void xRead_nonHolderWithBogusApproverUid_isRefusedAndAudited() {
        callerLacksView();
        stepUpRefuses();
        when(sessions.findByUid("S-BOGUS")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.xReadAuthorised("S-BOGUS",
                new AuthorisedReadRequest("01J0NOTAREALUSER00000000ZZ")))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(PosSessionAuthorisedReadTest::isShopFloorSafe);

        assertThat(refusalAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "REFUSED_NOT_AUTHORISED")
                .as("the uid that was tried is the whole point of auditing the refusal")
                .containsEntry("authorisedByUid", "01J0NOTAREALUSER00000000ZZ");
    }

    /**
     * Self-approval. The step-up service refuses a uid that resolves to the caller themselves, and
     * this test wires that real rule in (rather than a blanket "denied") so a regression that
     * short-circuits on "a uid was supplied" fails here instead of shipping a control a cashier can
     * satisfy alone.
     */
    @Test
    void xRead_selfApproval_isRefused() {
        callerLacksView();
        stepUpBehavesLikeTheRealService();
        when(sessions.findByUid("S-SELF")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() ->
                service.xReadAuthorised("S-SELF", new AuthorisedReadRequest(CASHIER_UID)))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(PosSessionAuthorisedReadTest::isShopFloorSafe);

        assertThat(refusalAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "REFUSED_NOT_AUTHORISED");
    }

    /**
     * A real, active, second human who simply does not hold {@code POS.SESSION.RECONCILE} — another
     * cashier called over from the next till. Being a second person is not the control; holding the
     * authority is.
     */
    @Test
    void xRead_approverWithoutTheReconcileAuthority_isRefused() {
        callerLacksView();
        stepUpBehavesLikeTheRealService();
        when(sessions.findByUid("S-PEER")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.xReadAuthorised("S-PEER",
                new AuthorisedReadRequest("01J0OTHERCASHIER000000000C")))
                .isInstanceOf(ForbiddenException.class);

        assertThat(refusalAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "REFUSED_NOT_AUTHORISED");
    }

    @Test
    void zRead_nonHolderWithNoApprover_isRefusedAndAudited() {
        callerLacksView();
        when(sessions.findByUid("S-Z-NONE")).thenReturn(Optional.of(reconciledSession()));

        assertThatThrownBy(() ->
                service.zReadAuthorised("S-Z-NONE", new AuthorisedReadRequest(null)))
                .isInstanceOf(ForbiddenException.class);

        assertThat(refusalAudit(AuditActions.POS_SESSION_ZREAD).detail())
                .containsEntry("outcome", "REFUSED_NO_APPROVAL");
    }

    // -------------------------------------------------------------------------
    // 4. The workflow rules are untouched — an approval opens a report, it does not
    //    create one or resurrect one
    // -------------------------------------------------------------------------

    /** X-read is refused on a RECONCILED session, approval or not: the Z-read is the final word. */
    @Test
    void xRead_onAReconciledSession_isStillRefused_evenWithAValidApprover() {
        callerLacksView();
        managerApproves();
        when(sessions.findByUid("S-STATE-X")).thenReturn(Optional.of(reconciledSession()));

        assertThatThrownBy(() ->
                service.xReadAuthorised("S-STATE-X", new AuthorisedReadRequest(MANAGER_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Z-read");
    }

    /** Z-read needs a settled shift. A manager's approval cannot conjure one early. */
    @Test
    void zRead_beforeReconciliation_isStillRefused_evenWithAValidApprover() {
        callerLacksView();
        managerApproves();
        when(sessions.findByUid("S-STATE-Z")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() ->
                service.zReadAuthorised("S-STATE-Z", new AuthorisedReadRequest(MANAGER_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has not been reconciled");
    }

    // -------------------------------------------------------------------------
    // 5. Every print is recorded, including the ones nobody had to approve
    // -------------------------------------------------------------------------

    /**
     * Owner decision 3. An X-read shows a shift's takings and the cash that should be in the drawer,
     * so "who read the till figures, and when" is the control — a trail that only fires on overrides
     * cannot tell an approved read from one nobody needed approval for.
     */
    @Test
    void theOrdinaryGetIsAuditedToo() {
        when(sessions.findByUid("S-GET")).thenReturn(Optional.of(openSession()));

        service.xRead("S-GET");

        assertThat(printedAudit(AuditActions.POS_SESSION_XREAD).detail())
                .containsEntry("outcome", "SERVED")
                .containsEntry("viewer", "asha.cashier")
                .containsEntry("authorisedBy", "");
    }

    @Test
    void theOrdinaryZReadGetIsAuditedToo() {
        when(sessions.findByUid("S-Z-GET")).thenReturn(Optional.of(reconciledSession()));

        service.zRead("S-Z-GET");

        assertThat(printedAudit(AuditActions.POS_SESSION_ZREAD).detail())
                .containsEntry("outcome", "SERVED");
    }

    /**
     * A refused read rolls its transaction back, so the audit row has to be written independently or
     * it rolls back with the refusal — losing precisely the attempt worth keeping.
     */
    @Test
    void refusalsAreRecordedIndependentlySoTheySurviveTheRollback() {
        callerLacksView();
        when(sessions.findByUid("S-INDEP")).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() ->
                service.xReadAuthorised("S-INDEP", new AuthorisedReadRequest(null)))
                .isInstanceOf(ForbiddenException.class);

        verify(audit, atLeastOnce()).recordIndependent(any());
        verify(audit, never()).record(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The caller holds POS.SESSION.VIEW in their own right. Still NON-root. */
    private void callerHoldsView() {
        when(permissionResolver.hasPermission(any(), eq(VIEW_PERMISSION), anyLong()))
                .thenReturn(true);
    }

    /** The shop owner has taken the X/Z reads off this role — the situation this feature is for. */
    private void callerLacksView() {
        when(permissionResolver.hasPermission(any(), eq(VIEW_PERMISSION), anyLong()))
                .thenReturn(false);
    }

    private void managerApproves() {
        when(stepUpAuth.verifyAuthoriserUid(eq(MANAGER_UID), eq(APPROVAL_PERMISSION), any()))
                .thenReturn(AuthorityVerificationDto.granted(
                        APPROVAL_PERMISSION, MANAGER_UID, "juma.manager", "Juma Mwita"));
    }

    private void stepUpRefuses() {
        when(stepUpAuth.verifyAuthoriserUid(anyString(), anyString(), any()))
                .thenReturn(AuthorityVerificationDto.denied(APPROVAL_PERMISSION,
                        "That approval could not be verified."));
    }

    /**
     * Mimics {@code StepUpAuthServiceImpl.verifyAuthoriserUid}'s actual contract: granted only for a
     * real second person who holds the code; refused for an unknown uid, for the caller themselves
     * (self-approval), and for a real user without the authority.
     */
    private void stepUpBehavesLikeTheRealService() {
        when(stepUpAuth.verifyAuthoriserUid(anyString(), anyString(), any()))
                .thenAnswer(call -> {
                    String uid = call.getArgument(0);
                    String code = call.getArgument(1);
                    RequestContext.Principal caller = RequestContext.get();
                    boolean isSelf = CASHIER_UID.equals(uid)
                            && caller != null && CASHIER_ID.equals(caller.userId());
                    // Only the manager holds POS.SESSION.RECONCILE; the peer cashier does not.
                    boolean holdsAuthority = MANAGER_UID.equals(uid);
                    if (isSelf || !holdsAuthority || !APPROVAL_PERMISSION.equals(code)) {
                        return AuthorityVerificationDto.denied(code,
                                "That approval could not be verified.");
                    }
                    return AuthorityVerificationDto.granted(code, uid, "juma.manager", "Juma Mwita");
                });
    }

    /** The audit event recorded for a SERVED print under {@code action}. */
    private AuditEvent printedAudit(String action) {
        return auditRow(action, outcome -> "SERVED".equals(outcome), "a SERVED print");
    }

    /** The audit event recorded for a refusal under {@code action}. */
    private AuditEvent refusalAudit(String action) {
        return auditRow(action, outcome -> outcome != null && outcome.startsWith("REFUSED"),
                "a refusal");
    }

    private AuditEvent auditRow(String action, java.util.function.Predicate<String> outcomeMatches,
                                 String what) {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeastOnce()).recordIndependent(captor.capture());
        return captor.getAllValues().stream()
                .filter(e -> action.equals(e.action()))
                .filter(e -> e.detail() != null
                        && outcomeMatches.test((String) e.detail().get("outcome")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No audit row for " + what + " on " + action + " — recorded: "
                                + captor.getAllValues().stream()
                                        .map(e -> e.action() + "/" + (e.detail() == null
                                                ? "-" : e.detail().get("outcome")))
                                        .toList()));
    }

    /**
     * A refusal a cashier reads on a shop screen: plain language, and never an HTTP status, a
     * permission code, a uid, or anything else that belongs in a log.
     */
    private static void isShopFloorSafe(Throwable ex) {
        assertThat(ex.getMessage())
                .doesNotContain("POS.")
                .doesNotContain("403")
                .doesNotContain("Forbidden")
                .doesNotContain("Exception")
                .doesNotContain("null")
                .doesNotContain("01J0");
    }

    private void stubFigures() {
        when(invoices.sumGrossByPosSession(any())).thenReturn(new BigDecimal("800.00"));
        when(invoices.countByPosSession(any())).thenReturn(3L);
        when(tenderPayments.sumCashTenderByPosSession(any())).thenReturn(new BigDecimal("500.00"));
        when(tenderPayments.sumByPosSessionGroupedByTender(any())).thenReturn(List.of());
        when(payouts.totalPayoutsForSession(any())).thenReturn(BigDecimal.ZERO);
        when(payouts.payoutBreakdownForSession(any())).thenReturn(List.of());
    }

    /** Opening float 1000 + 500 cash tender − 0 payouts = 1500 expected. */
    private PosSession openSession() {
        PosSession s = new PosSession(SESSION_COMPANY, 1L, 5L, CASHIER_ID, "POS-0001",
                new BigDecimal("1000.00"), CASHIER_ID);
        s.setStatus(PosSessionStatus.OPEN);
        return s;
    }

    private PosSession reconciledSession() {
        PosSession s = openSession();
        s.setStatus(PosSessionStatus.RECONCILED);
        s.setClosedAt(Instant.parse("2026-08-11T18:00:00Z"));
        s.setReconciledAt(Instant.parse("2026-08-11T18:05:00Z"));
        s.setExpectedCashAmount(new BigDecimal("1500.00"));
        s.setCountedCashAmount(new BigDecimal("1500.00"));
        s.setVarianceAmount(BigDecimal.ZERO);
        return s;
    }
}
