package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnedPayload;
import com.erp.modules.sales.domain.entity.PosSaleIdempotency;
import com.erp.modules.sales.domain.entity.PosSaleReturn;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSaleReturnLineRepository;
import com.erp.modules.sales.repository.PosSaleReturnRepository;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ADR-0072 item returns at service level: the over-return guard, the authority rules (the reverse's,
 * rule for rule), state refusals, idempotent replay, and what a successful return writes.
 */
class PosSaleReturnServiceImplTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 2L;
    private static final Long USER_ID    = 10L;
    private static final Long INV_ID     = 500L;
    private static final String INV_UID  = "INV-UID-000000000000000001";
    private static final String LINE_UID = "LINE-UID-00000000000000001";
    private static final String MANAGER_UID = "01J0MANAGER0000000000000AA";
    private static final String SUPERVISOR_PERMISSION = "SALES.INVOICE.VOID";

    private SalesInvoiceRepository invoices;
    private SalesInvoiceLineRepository invoiceLines;
    private SalesInvoicePaymentRepository tenders;
    private PosSessionRepository sessions;
    private PosSessionPayoutRepository payouts;
    private PosSaleReturnRepository returns;
    private PosSaleReturnLineRepository returnLines;
    private PosSaleIdempotencyRepository idempotency;
    private OrderToCashNumberGenerator numbers;
    private ArBalanceService arBalance;
    private StepUpAuthService stepUpAuth;
    private UserLookupService users;
    private PermissionResolver permissions;
    private AuditService audit;
    private OutboxPublisher outbox;

    private PosSaleReturnServiceImpl service;
    private SalesInvoice invoice;
    private PosSession session;
    private SalesInvoiceLine soldLine;

    @BeforeEach
    void setUp() {
        invoices     = mock(SalesInvoiceRepository.class);
        invoiceLines = mock(SalesInvoiceLineRepository.class);
        tenders      = mock(SalesInvoicePaymentRepository.class);
        sessions     = mock(PosSessionRepository.class);
        payouts      = mock(PosSessionPayoutRepository.class);
        returns      = mock(PosSaleReturnRepository.class);
        returnLines  = mock(PosSaleReturnLineRepository.class);
        idempotency  = mock(PosSaleIdempotencyRepository.class);
        numbers      = mock(OrderToCashNumberGenerator.class);
        arBalance    = mock(ArBalanceService.class);
        stepUpAuth   = mock(StepUpAuthService.class);
        users        = mock(UserLookupService.class);
        permissions  = mock(PermissionResolver.class);
        audit        = mock(AuditService.class);
        outbox       = mock(OutboxPublisher.class);
        ProductRepository products = mock(ProductRepository.class);

        service = new PosSaleReturnServiceImpl(products, invoices, invoiceLines, tenders, sessions,
                payouts, returns, returnLines, idempotency, numbers, arBalance, stepUpAuth, users,
                permissions, mock(ScopeGuard.class), audit, outbox, CurrencyMinorUnits.FALLBACK);

        RequestContext.set(new RequestContext.Principal(USER_ID, "cashier", false, COMPANY_ID,
                BRANCH_ID, "127.0.0.1"));

        invoice = mock(SalesInvoice.class);
        when(invoice.getId()).thenReturn(INV_ID);
        when(invoice.getUid()).thenReturn(INV_UID);
        when(invoice.getInvoiceNumber()).thenReturn("INV-0001");
        when(invoice.getCompanyId()).thenReturn(COMPANY_ID);
        when(invoice.getBranchId()).thenReturn(BRANCH_ID);
        when(invoice.getOrigin()).thenReturn(DocumentOrigin.POS);
        when(invoice.getStatus()).thenReturn(InvoiceStatus.FINALISED);
        when(invoice.getPosSessionId()).thenReturn(99L);
        when(invoice.getCurrency()).thenReturn(CurrencyCode.of("TZS"));
        when(invoice.getGrossTotalAmount()).thenReturn(new BigDecimal("2360"));
        when(invoices.findByUid(INV_UID)).thenReturn(Optional.of(invoice));
        when(invoices.lockByCompanyIdAndId(COMPANY_ID, INV_ID)).thenReturn(Optional.of(invoice));

        session = mock(PosSession.class);
        when(session.getId()).thenReturn(99L);
        when(session.getUid()).thenReturn("SESSION-UID-0000000000001");
        when(session.getStatus()).thenReturn(PosSessionStatus.OPEN);
        when(session.getCashierId()).thenReturn(USER_ID);
        when(sessions.findByCompanyIdAndId(COMPANY_ID, 99L)).thenReturn(Optional.of(session));

        // One sold line: 2 pcs, net 2000, vat 360 (gross 2360), 2 base units.
        soldLine = mock(SalesInvoiceLine.class);
        when(soldLine.getId()).thenReturn(7L);
        when(soldLine.getUid()).thenReturn(LINE_UID);
        when(soldLine.getProductId()).thenReturn(30L);
        when(soldLine.getProductName()).thenReturn("Widget");
        when(soldLine.getQuantity()).thenReturn(new BigDecimal("2"));
        when(soldLine.getQtyInBase()).thenReturn(new BigDecimal("2"));
        when(soldLine.getNetAmount()).thenReturn(new BigDecimal("2000"));
        when(soldLine.getVatAmount()).thenReturn(new BigDecimal("360"));
        when(soldLine.getLineDiscountAmount()).thenReturn(null);
        when(invoiceLines.findByInvoiceIdOrderByLineNo(INV_ID)).thenReturn(List.of(soldLine));

        SalesInvoicePayment cash = mock(SalesInvoicePayment.class);
        when(cash.getTenderType()).thenReturn(TenderType.CASH);
        when(cash.getAmount()).thenReturn(new BigDecimal("2360"));
        when(tenders.findByInvoiceId(INV_ID)).thenReturn(List.of(cash));

        Product product = mock(Product.class);
        when(product.getUid()).thenReturn("PRODUCT-UID-00000000001");
        when(products.findByCompanyIdAndId(COMPANY_ID, 30L)).thenReturn(Optional.of(product));

        when(returnLines.totalsByCompanyIdAndSoldLines(eq(COMPANY_ID), anyCollection()))
                .thenReturn(List.of());
        when(returns.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
        when(payouts.save(any())).thenAnswer(a -> a.getArgument(0));
        when(numbers.nextPosReturn(COMPANY_ID)).thenReturn("PRT-0001");
        when(idempotency.tryReserve(any(), any())).thenReturn(1);
        when(users.displayNamesByIds(any())).thenReturn(Map.of());

        // The caller is a supervisor by default; individual tests take that away.
        when(permissions.hasPermission(any(), eq(SUPERVISOR_PERMISSION), anyLong()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private static PosSaleReturnRequest request(String qty, String authoriserUid) {
        return new PosSaleReturnRequest("customer changed mind", authoriserUid,
                List.of(new PosSaleReturnRequest.LineDto(LINE_UID, new BigDecimal(qty))));
    }

    private static PosReturnedLineTotalsDto returned(String qty, String net, String vat) {
        BigDecimal n = new BigDecimal(net);
        BigDecimal v = new BigDecimal(vat);
        return new PosReturnedLineTotalsDto(7L, new BigDecimal(qty), new BigDecimal(qty), n,
                BigDecimal.ZERO, v, n.add(v));
    }

    // ---- what a successful return writes ------------------------------------------------------

    @Test
    void partialReturn_writesReturnPayoutAndOutboxEvent_withProRatedAmounts() {
        service.returnItems(INV_UID, null, request("1", null));

        ArgumentCaptor<PosSaleReturn> header = ArgumentCaptor.forClass(PosSaleReturn.class);
        verify(returns).saveAndFlush(header.capture());
        assertThat(header.getValue().getNetAmount()).isEqualByComparingTo("1000");
        assertThat(header.getValue().getVatAmount()).isEqualByComparingTo("180");
        assertThat(header.getValue().getGrossAmount()).isEqualByComparingTo("1180");
        assertThat(header.getValue().getReturnNo()).isEqualTo("PRT-0001");
        assertThat(header.getValue().getAuthorisedBy())
                .as("a supervisor acted on their own authority").isNull();

        ArgumentCaptor<PosSessionPayout> payout = ArgumentCaptor.forClass(PosSessionPayout.class);
        verify(payouts).save(payout.capture());
        assertThat(payout.getValue().getPayoutType()).isEqualTo(PosPayoutType.REFUND);
        assertThat(payout.getValue().getAmount()).isEqualByComparingTo("1180");
        assertThat(payout.getValue().getSourceInvoiceUid()).isEqualTo(INV_UID);
        assertThat(payout.getValue().isPostedToGl())
                .as("a REFUND payout must not carry a journal of its own").isFalse();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(DomainEventType.POS_SALE_RETURNED), anyString(), any(), any(),
                eq(COMPANY_ID), eq(BRANCH_ID), event.capture());
        var payload = (PosSaleReturnedPayload) event.getValue();
        assertThat(payload.grossAmount()).isEqualByComparingTo("1180");
        assertThat(payload.lines()).singleElement().satisfies(l -> {
            assertThat(l.productId()).isEqualTo(30L);
            assertThat(l.qtyReturnedBase()).isEqualByComparingTo("1");
        });
    }

    @Test
    void finalReturnOfTheRemainder_takesTheExactRemainder() {
        // 1 of 2 already returned: net 1000 / vat 180 given back.
        when(returnLines.totalsByCompanyIdAndSoldLines(eq(COMPANY_ID), anyCollection()))
                .thenReturn(List.of(returned("1", "1000", "180")));

        service.returnItems(INV_UID, null, request("1", null));

        ArgumentCaptor<PosSaleReturn> header = ArgumentCaptor.forClass(PosSaleReturn.class);
        verify(returns).saveAndFlush(header.capture());
        assertThat(header.getValue().getGrossAmount()).isEqualByComparingTo("1180");
    }

    // ---- over-return and malformed input -------------------------------------------------------

    @Test
    void returningMoreThanWasSold_isRefusedWithAFriendlySentence() {
        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("3", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only 2 of Widget can still be returned.");
        verify(payouts, never()).save(any());
        verify(outbox, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void returningMoreThanRemains_countsWhatWasAlreadyReturned() {
        when(returnLines.totalsByCompanyIdAndSoldLines(eq(COMPANY_ID), anyCollection()))
                .thenReturn(List.of(returned("1.5", "1500", "270")));

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only 0.5 of Widget can still be returned.");
    }

    @Test
    void aFullyReturnedLine_saysSo() {
        when(returnLines.totalsByCompanyIdAndSoldLines(eq(COMPANY_ID), anyCollection()))
                .thenReturn(List.of(returned("2", "2000", "360")));

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Widget has already been fully returned.");
    }

    @Test
    void sameLineTwiceInOneRequest_isRefused() {
        var twice = new PosSaleReturnRequest("duplicate line", null, List.of(
                new PosSaleReturnRequest.LineDto(LINE_UID, BigDecimal.ONE),
                new PosSaleReturnRequest.LineDto(LINE_UID, BigDecimal.ONE)));

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void zeroOrNegativeQuantity_isRefused() {
        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("0", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("-1", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineFromAnotherSale_isRefused() {
        var other = new PosSaleReturnRequest("wrong line", null, List.of(
                new PosSaleReturnRequest.LineDto("SOME-OTHER-LINE-UID-0001", BigDecimal.ONE)));

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, other))
                .isInstanceOf(ConflictException.class);
    }

    // ---- state ---------------------------------------------------------------------------------

    @Test
    void aReversedSale_cannotTakeAReturn() {
        when(invoice.getStatus()).thenReturn(InvoiceStatus.VOID);

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already reversed");
    }

    @Test
    void aClosedSession_cannotTakeAReturn() {
        when(session.getStatus()).thenReturn(PosSessionStatus.CLOSED);

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("session is closed");
    }

    @Test
    void aNonPosInvoice_cannotTakeATillReturn() {
        when(invoice.getOrigin()).thenReturn(DocumentOrigin.DIRECT);

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not a POS sale");
    }

    @Test
    void aSaleWithArAllocations_isRefused() {
        when(arBalance.hasAllocations(COMPANY_ID, INV_UID)).thenReturn(true);

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("credit note");
    }

    @Test
    void aSaleNotPaidInFull_orGivenAsComplimentary_hasNoCashToRefund() {
        SalesInvoicePayment part = mock(SalesInvoicePayment.class);
        when(part.getTenderType()).thenReturn(TenderType.CASH);
        when(part.getAmount()).thenReturn(new BigDecimal("1000"));
        when(tenders.findByInvoiceId(INV_ID)).thenReturn(List.of(part));
        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not paid in full");

        SalesInvoicePayment comp = mock(SalesInvoicePayment.class);
        when(comp.getTenderType()).thenReturn(TenderType.COMPLIMENTARY);
        when(tenders.findByInvoiceId(INV_ID)).thenReturn(List.of(comp));
        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("complimentary");
    }

    // ---- authority: the reverse's rules ---------------------------------------------------------

    @Test
    void aCashier_returningFromAnotherCashiersSession_isRefused_andAudited() {
        when(permissions.hasPermission(any(), eq(SUPERVISOR_PERMISSION), anyLong()))
                .thenReturn(false);
        when(session.getCashierId()).thenReturn(66L);

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", MANAGER_UID)))
                .isInstanceOf(ForbiddenException.class);
        verify(audit).recordIndependent(any());
        verify(payouts, never()).save(any());
    }

    @Test
    void aCashier_withoutAManagersApproval_isRefused() {
        when(permissions.hasPermission(any(), eq(SUPERVISOR_PERMISSION), anyLong()))
                .thenReturn(false);
        when(stepUpAuth.verifyAuthoriserUid(any(), eq(SUPERVISOR_PERMISSION), any()))
                .thenReturn(AuthorityVerificationDto.denied(SUPERVISOR_PERMISSION, "no"));

        assertThatThrownBy(() -> service.returnItems(INV_UID, null, request("1", null)))
                .isInstanceOf(ForbiddenException.class);
        verify(audit).recordIndependent(any());
        verify(payouts, never()).save(any());
    }

    @Test
    void aCashier_withAVerifiedManager_succeeds_andStepUpIsReverifiedInTheInvoicesCompany() {
        when(permissions.hasPermission(any(), eq(SUPERVISOR_PERMISSION), anyLong()))
                .thenReturn(false);
        when(stepUpAuth.verifyAuthoriserUid(eq(MANAGER_UID), eq(SUPERVISOR_PERMISSION), any()))
                .thenReturn(AuthorityVerificationDto.granted(SUPERVISOR_PERMISSION, MANAGER_UID,
                        "mgr", "The Manager"));
        when(users.idByUid(MANAGER_UID)).thenReturn(Optional.of(77L));

        service.returnItems(INV_UID, null, request("1", MANAGER_UID));

        verify(stepUpAuth).verifyAuthoriserUid(MANAGER_UID, SUPERVISOR_PERMISSION, COMPANY_ID);
        ArgumentCaptor<PosSaleReturn> header = ArgumentCaptor.forClass(PosSaleReturn.class);
        verify(returns).saveAndFlush(header.capture());
        assertThat(header.getValue().getAuthorisedBy()).isEqualTo(77L);
    }

    // ---- idempotency ---------------------------------------------------------------------------

    @Test
    void aRetriedKey_returnsTheOriginalReturn_andRefundsNothingTwice() {
        PosSaleIdempotency marker = mock(PosSaleIdempotency.class);
        when(marker.getInvoiceUid()).thenReturn("RETURN-UID-0000000000001");
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, "RET:key-1"))
                .thenReturn(Optional.of(marker));
        PosSaleReturn original = mock(PosSaleReturn.class);
        when(original.getId()).thenReturn(5L);
        when(original.getCompanyId()).thenReturn(COMPANY_ID);
        when(original.getSalesInvoiceId()).thenReturn(INV_ID);
        when(original.getPosSessionId()).thenReturn(99L);
        when(original.getReturnNo()).thenReturn("PRT-0001");
        when(returns.findByCompanyIdAndUid(COMPANY_ID, "RETURN-UID-0000000000001"))
                .thenReturn(Optional.of(original));

        var dto = service.returnItems(INV_UID, "key-1", request("1", null));

        assertThat(dto.returnNo()).isEqualTo("PRT-0001");
        verify(payouts, never()).save(any());
        verify(outbox, never()).publish(any(), any(), any(), any(), any(), any(), any());
        verify(returns, never()).saveAndFlush(any());
    }

    @Test
    void aKeyFromADifferentSale_isNotReplayed() {
        PosSaleIdempotency marker = mock(PosSaleIdempotency.class);
        when(marker.getInvoiceUid()).thenReturn("RETURN-UID-0000000000001");
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, "RET:key-1"))
                .thenReturn(Optional.of(marker));
        PosSaleReturn original = mock(PosSaleReturn.class);
        when(original.getSalesInvoiceId()).thenReturn(999L);
        when(returns.findByCompanyIdAndUid(COMPANY_ID, "RETURN-UID-0000000000001"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.returnItems(INV_UID, "key-1", request("1", null)))
                .isInstanceOf(ConflictException.class);
        verify(payouts, never()).save(any());
    }

    @Test
    void theKeyIsReservedThenStampedWithTheReturnsUid() {
        service.returnItems(INV_UID, "key-2", request("1", null));

        verify(idempotency).tryReserve(COMPANY_ID, "RET:key-2");
        verify(idempotency).stampInvoiceUid(eq(COMPANY_ID), eq("RET:key-2"), any());
    }
}
