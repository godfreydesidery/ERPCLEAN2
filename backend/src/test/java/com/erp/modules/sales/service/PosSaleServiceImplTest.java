package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.entity.PosSaleIdempotency;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.PosSaleFlowStatus;
import com.erp.modules.sales.domain.enums.PosSaleLookupVerdict;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ADR-0044 D-3a age-restriction gate in {@link PosSaleServiceImpl#processSale}.
 *
 * <p>All repository/service collaborators are mocked so the gate logic can be exercised in
 * isolation without a database or Spring context.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Restricted product + no ageVerified + no override → 409 ConflictException.</li>
 *   <li>Restricted product + ageVerified=true → proceeds past the gate (no exception).</li>
 *   <li>Restricted product + no ageVerified + caller holds POS.SALE.AGE_OVERRIDE → proceeds.</li>
 *   <li>NONE (unrestricted) product → no gate interaction, proceeds normally.</li>
 * </ol>
 */
class PosSaleServiceImplTest {

    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 2L;
    private static final Long   USER_ID     = 10L;
    private static final Long   CUSTOMER_ID = 20L;
    private static final Long   PRODUCT_ID  = 30L;
    private static final Long   UNIT_ID     = 40L;
    private static final String SESSION_UID = "SESSION-001";
    private static final String IDEM_KEY    = "IDEM-0001";
    /** Kept as a literal so a rename of the production constant is caught here, not at the till. */
    private static final String REVERSAL_PERMISSION = "SALES.INVOICE.VOID";
    /** A manager uid a step-up would have returned. */
    private static final String MANAGER_UID = "01J0MANAGER0000000000000AA";

    private static final Duration REPLAY_MAX_AGE        = Duration.ofMinutes(15);
    private static final Duration SALE_MAX_AGE          = Duration.ofHours(12);
    private static final Duration MAX_OPEN_SESSION_AGE  = Duration.ofHours(36);

    private PosSessionRepository        posSessionRepo;
    private SalesInvoiceRepository      invoiceRepo;
    private SalesInvoiceService         invoiceService;
    private ProductRepository           products;
    private UnitOfMeasureRepository     units;
    private CustomerRepository          customers;
    private CompanyRepository           companies;
    private ScopeGuard                  scopeGuard;
    private AuditService                audit;
    private PosSaleIdempotencyRepository idempotency;
    private PermissionResolver          permissionResolver;
    private AgentRepository             agents;
    private StepUpAuthService           stepUpAuth;

    private PosSaleServiceImpl service;

    @BeforeEach
    void setUp() {
        posSessionRepo     = mock(PosSessionRepository.class);
        invoiceRepo        = mock(SalesInvoiceRepository.class);
        invoiceService     = mock(SalesInvoiceService.class);
        products           = mock(ProductRepository.class);
        units              = mock(UnitOfMeasureRepository.class);
        customers          = mock(CustomerRepository.class);
        companies          = mock(CompanyRepository.class);
        scopeGuard         = mock(ScopeGuard.class);
        audit              = mock(AuditService.class);
        idempotency        = mock(PosSaleIdempotencyRepository.class);
        permissionResolver = mock(PermissionResolver.class);
        agents             = mock(AgentRepository.class);
        stepUpAuth         = mock(StepUpAuthService.class);

        service = new PosSaleServiceImpl(
                posSessionRepo, invoiceRepo, invoiceService, products, units,
                customers, companies, scopeGuard, audit, idempotency, permissionResolver,
                agents, stepUpAuth, REPLAY_MAX_AGE, SALE_MAX_AGE, MAX_OPEN_SESSION_AGE);

        // Common stubs: open session in company scope
        PosSession session = buildSession();
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(session));

        Customer customer = mock(Customer.class);
        when(customer.getUid()).thenReturn("CUST-001");
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getUid()).thenReturn("CO-001");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        // idempotency: always returns 1 (key claimed, not a duplicate)
        when(idempotency.tryReserve(any(), any())).thenReturn(1);

        // invoice service stubs — minimal happy path through the non-gate steps
        SalesInvoiceDto draftDto = mock(SalesInvoiceDto.class);
        when(draftDto.uid()).thenReturn("INV-001");
        when(invoiceService.create(any())).thenReturn(draftDto);

        com.erp.modules.sales.domain.entity.SalesInvoice invoice =
                mock(com.erp.modules.sales.domain.entity.SalesInvoice.class);
        when(invoice.getGrossTotalAmount()).thenReturn(BigDecimal.TEN);
        when(invoiceRepo.findByUid("INV-001")).thenReturn(Optional.of(invoice));
        when(invoiceRepo.saveAndFlush(any())).thenReturn(invoice);

        SalesInvoiceDto finalDto = mock(SalesInvoiceDto.class);
        when(invoiceService.getByUid("INV-001")).thenReturn(finalDto);

        UnitOfMeasure unit = mock(UnitOfMeasure.class);
        when(unit.getUid()).thenReturn("UOM-001");
        when(units.findById(UNIT_ID)).thenReturn(Optional.of(unit));

        // default: no POS.SALE.AGE_OVERRIDE
        when(permissionResolver.hasPermission(any(), eq("POS.SALE.AGE_OVERRIDE"), any(long.class)))
                .thenReturn(false);
        // default: a plain cashier — no supervisor void authority, and no approval on file
        when(permissionResolver.hasPermission(any(), eq(REVERSAL_PERMISSION), any(long.class)))
                .thenReturn(false);
        when(stepUpAuth.verifyAuthoriserUid(any(), any(), any()))
                .thenReturn(AuthorityVerificationDto.denied(REVERSAL_PERMISSION, "no"));

        // Set a non-root principal in RequestContext
        RequestContext.set(new RequestContext.Principal(
                USER_ID, "cashier", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: restricted product + no ack + no override → 409
    // =========================================================================

    @Test
    void processSale_restrictedProduct_noAck_noOverride_throws409() {
        Product p = product(RestrictedKind.AGE_18);    // build before entering when() chain
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // ageVerified omitted (null)

        assertThatThrownBy(() -> service.processSale(null, req))
                .as("AGE_18 product without ack or override must be blocked with 409")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Age-restricted")
                .hasMessageContaining("ageVerified=true");
    }

    @Test
    void processSale_restrictedProduct_ageVerifiedFalse_noOverride_throws409() {
        Product p = product(RestrictedKind.AGE_21);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(false);  // explicit false

        assertThatThrownBy(() -> service.processSale(null, req))
                .as("AGE_21 product with ageVerified=false must be blocked")
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Bar 2: restricted product + ageVerified=true → proceeds
    // =========================================================================

    @Test
    void processSale_restrictedProduct_ageVerifiedTrue_proceeds() {
        Product p = product(RestrictedKind.AGE_18);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(true);

        assertThatCode(() -> service.processSale(null, req))
                .as("AGE_18 product with ageVerified=true must not throw")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 3: restricted product + no ack + caller holds POS.SALE.AGE_OVERRIDE → proceeds
    // =========================================================================

    @Test
    void processSale_restrictedProduct_noAck_callerHasOverride_proceeds() {
        when(permissionResolver.hasPermission(any(), eq("POS.SALE.AGE_OVERRIDE"), any(long.class)))
                .thenReturn(true);
        Product p = product(RestrictedKind.AGE_18);    // build after re-stubbing permissionResolver
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // no ack, but override held

        assertThatCode(() -> service.processSale(null, req))
                .as("AGE_18 product with POS.SALE.AGE_OVERRIDE must not throw even without ack")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 4: NONE product → gate is inert (no exception regardless of ageVerified)
    // =========================================================================

    @Test
    void processSale_noneProduct_noAck_noOverride_proceeds() {
        Product p = product(RestrictedKind.NONE);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        PosSaleRequest req = request(null);   // no ack needed

        assertThatCode(() -> service.processSale(null, req))
                .as("NONE (unrestricted) product must never be gated")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // FIX 2 (busy-day sim, CRITICAL): POS reverse/void must not route through the AR-oriented
    // settled-tender guard — every POS sale is paid at the till, so that guard made
    // POS.SALE.VOID permanently unsatisfiable (always 409).
    // =========================================================================

    @Test
    void reverseSale_posOriginOpenSession_callsVoidPosInvoice_notVoidInvoice() {
        givenOpenPosSaleRungBy(USER_ID);
        givenManagerApproves();

        service.reverseSale("INV-999", new VoidInvoiceRequest("wrong item", MANAGER_UID));

        verify(invoiceService).voidPosInvoice(eq("INV-999"), any(VoidInvoiceRequest.class));
        verify(invoiceService, never()).voidInvoice(any(), any());
    }

    // =========================================================================
    // K1 follow-up C3 — refund authorisation used to be decoration.
    //
    // Two independent holes, both verified live against the running stack before the fix:
    //   (a) reverseSale checked company scope, POS origin and "session is OPEN" and NOTHING else,
    //       so uat_cashier reversed INV-0397 out of a DIFFERENT cashier's open drawer;
    //   (b) the manager password the till asked for never left the client, so curl skipped it.
    // =========================================================================

    /** (a) A stranger's open drawer is not a cashier's to reach into. */
    @Test
    void reverseSale_saleBelongsToAnotherCashier_isRefused() {
        givenOpenPosSaleRungBy(66L);        // the sale was rung by cashier 66; the caller is 10
        givenManagerApproves();             // even WITH a valid manager approval

        assertThatThrownBy(() -> service.reverseSale(
                "INV-999", new VoidInvoiceRequest("not mine", MANAGER_UID)))
                .as("a cashier must not reverse a sale from another cashier's session")
                .isInstanceOf(ForbiddenException.class);

        verify(invoiceService, never()).voidPosInvoice(any(), any());
    }

    /** (a) …unless the caller carries the supervisor authority, which no cashier bundle holds. */
    @Test
    void reverseSale_anotherCashiersSale_allowedForASupervisor() {
        givenOpenPosSaleRungBy(66L);
        when(permissionResolver.hasPermission(any(), eq(REVERSAL_PERMISSION), any(long.class)))
                .thenReturn(true);

        service.reverseSale("INV-999", new VoidInvoiceRequest("supervisor correction", null));

        verify(invoiceService).voidPosInvoice(eq("INV-999"), any(VoidInvoiceRequest.class));
        // A supervisor IS the authority — they must not be asked to find a second one.
        verify(stepUpAuth, never()).verifyAuthoriserUid(any(), any(), any());
    }

    /** (b) The cashier's OWN sale still needs a manager: no approval on the request, no refund. */
    @Test
    void reverseSale_ownSaleWithoutAnyApproval_isRefused() {
        givenOpenPosSaleRungBy(USER_ID);

        assertThatThrownBy(() -> service.reverseSale("INV-999", new VoidInvoiceRequest("oops", null)))
                .as("a refund on the cashier's own authority alone is the shrinkage route the "
                        + "step-up exists to close")
                .isInstanceOf(ForbiddenException.class);

        verify(invoiceService, never()).voidPosInvoice(any(), any());
    }

    /** (b) A uid the client invented is refused — the server re-resolves it, never trusts it. */
    @Test
    void reverseSale_fabricatedAuthoriserUid_isRefused() {
        givenOpenPosSaleRungBy(USER_ID);
        // stepUpAuth default stub already denies — this is precisely the "curl made one up" case
        assertThatThrownBy(() -> service.reverseSale(
                "INV-999", new VoidInvoiceRequest("refund", "01JFAKEFAKEFAKEFAKEFAKEFAK")))
                .isInstanceOf(ForbiddenException.class);

        verify(invoiceService, never()).voidPosInvoice(any(), any());
    }

    /** (b) The authoriser is re-verified against the invoice's OWN company, never a caller param. */
    @Test
    void reverseSale_reverifiesTheAuthoriserInTheInvoicesCompany() {
        givenOpenPosSaleRungBy(USER_ID);
        givenManagerApproves();

        service.reverseSale("INV-999", new VoidInvoiceRequest("refund", MANAGER_UID));

        verify(stepUpAuth).verifyAuthoriserUid(MANAGER_UID, REVERSAL_PERMISSION, COMPANY_ID);
    }

    // ---- reversal fixtures --------------------------------------------------

    /** An OPEN POS sale (INV-999) whose session was opened by {@code cashierId}. */
    private void givenOpenPosSaleRungBy(Long cashierId) {
        SalesInvoice inv = mock(SalesInvoice.class);
        when(inv.getCompanyId()).thenReturn(COMPANY_ID);
        when(inv.getOrigin()).thenReturn(DocumentOrigin.POS);
        when(inv.getPosSessionId()).thenReturn(99L);
        when(inv.getId()).thenReturn(500L);
        when(inv.getUid()).thenReturn("INV-999");
        when(invoiceRepo.findByUid("INV-999")).thenReturn(Optional.of(inv));
        // buildSession() (setUp's stubbed OPEN session) has id=99L — matches inv.getPosSessionId()
        PosSession openSession = buildSession();
        when(openSession.getCashierId()).thenReturn(cashierId);
        when(posSessionRepo.findById(99L)).thenReturn(Optional.of(openSession));
    }

    /** A real, active, different manager who genuinely holds the reversal authority. */
    private void givenManagerApproves() {
        when(stepUpAuth.verifyAuthoriserUid(eq(MANAGER_UID), eq(REVERSAL_PERMISSION), any()))
                .thenReturn(AuthorityVerificationDto.granted(
                        REVERSAL_PERMISSION, MANAGER_UID, "uat_salesmgr", "UAT Sales Manager"));
    }

    // =========================================================================
    // K11 — the unfinished-sale loop. Three compounding causes, one per group below.
    // =========================================================================

    /**
     * Cause 1 (ORDERING). The stored answer must survive the shift.
     *
     * <p>The OPEN-session check used to run before the idempotency marker was read, so the moment
     * the cashier closed the till the recorded "yes, this sale posted" became permanently
     * unreachable — every retry answered "session is not OPEN" and the dialog re-armed forever.
     */
    @Test
    void processSale_closedSession_stillResolvesAStampedKeyToTheOriginalSale() {
        // Built before the when() chains: these helpers stub mocks of their own, and Mockito treats a
        // nested when() inside an in-progress stubbing as UnfinishedStubbing.
        PosSession session = closedSession();
        PosSaleIdempotency marker = stampedMarker("INV-001");
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(session));
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));

        var result = service.processSale(IDEM_KEY, request(null));

        assertThat(result)
                .as("a stamped idempotency marker is a definitive answer and must be readable "
                        + "whatever state the session is in now")
                .isSameAs(invoiceService.getByUid("INV-001"));
        verify(idempotency, never()).tryReserve(any(), any());
        verify(invoiceService, never()).create(any());
    }

    /**
     * Cause 2 (STATUS COLLISION), and the second defect: a first-attempt business rejection used to
     * be indistinguishable from a real in-flight sale, so the till armed a pending slot for a sale
     * that was never started — a ghost that blocked the drawer with nothing to resolve.
     */
    @Test
    void processSale_closedSessionWithNoMarker_isRejectedNotInFlight() {
        PosSession session = closedSession();
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(session));
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.empty());
        when(idempotency.tryReserve(COMPANY_ID, IDEM_KEY)).thenReturn(1);
        PosSaleRequest req = request(null);

        assertThatThrownBy(() -> service.processSale(IDEM_KEY, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .as("a closed session is a business rejection — the client must NOT arm a pending sale")
                .isEqualTo(PosSaleFlowStatus.REJECTED);
    }

    /** The same disambiguation for a rejection raised deep inside the sale (tenders short of gross). */
    @Test
    void processSale_underTender_isRejectedNotInFlight() {
        Product p = product(RestrictedKind.NONE);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        // gross is stubbed at 10 in setUp; tender only 1
        PosSaleRequest req = new PosSaleRequest(
                SESSION_UID, CUSTOMER_ID, null, "TZS",
                List.of(new PosSaleRequest.LineItem(PRODUCT_ID, UNIT_ID, BigDecimal.ONE, null, null)),
                List.of(new PosSaleRequest.PosTender(
                        TenderType.CASH, BigDecimal.ONE, null, null, null, null, null)),
                BigDecimal.ONE, null, null);

        assertThatThrownBy(() -> service.processSale(null, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .as("an under-tender is a rejection, not a sale in flight")
                .isEqualTo(PosSaleFlowStatus.REJECTED);
    }

    /** A genuinely unfinished attempt — and only this — reads as IN_FLIGHT. */
    @Test
    void processSale_youngUnstampedMarker_isInFlight() {
        PosSaleIdempotency marker = unstampedMarker(Instant.now());
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));
        PosSaleRequest req = request(null);

        assertThatThrownBy(() -> service.processSale(IDEM_KEY, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .isEqualTo(PosSaleFlowStatus.IN_FLIGHT);
    }

    /**
     * The loop's terminating state: an attempt that reserved a key and then never finished must stop
     * reporting "still being processed" once it is plainly abandoned.
     */
    @Test
    void processSale_abandonedMarker_terminatesAsStaleReplay() {
        PosSaleIdempotency marker =
                unstampedMarker(Instant.now().minus(REPLAY_MAX_AGE).minusSeconds(60));
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));
        PosSaleRequest req = request(null);

        assertThatThrownBy(() -> service.processSale(IDEM_KEY, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .as("an abandoned marker must terminate the loop, not feed it")
                .isEqualTo(PosSaleFlowStatus.STALE_REPLAY);
    }

    /**
     * The danger the age gate exists for: a days-old basket replayed into a still-open session would
     * be re-priced and re-stocked into the current period with nothing to stop it.
     */
    @Test
    void processSale_basketCapturedTooLongAgo_isRefusedNotPosted() {
        PosSaleRequest req = requestCapturedAt(Instant.now().minus(SALE_MAX_AGE).minusSeconds(60));

        assertThatThrownBy(() -> service.processSale(null, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .isEqualTo(PosSaleFlowStatus.STALE_REPLAY);
        verify(invoiceService, never()).create(any());
    }

    /** A basket captured moments ago is normal traffic and must be untouched by the gate. */
    @Test
    void processSale_freshlyCapturedBasket_proceeds() {
        Product p = product(RestrictedKind.NONE);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));

        assertThatCode(() -> service.processSale(null, requestCapturedAt(Instant.now())))
                .doesNotThrowAnyException();
    }

    /** A session left open across days can no longer absorb new sales into its Z-read. */
    @Test
    void processSale_sessionOpenTooLong_isRefused() {
        PosSession stale = buildSession();
        when(stale.getOpenedAt())
                .thenReturn(Instant.now().minus(MAX_OPEN_SESSION_AGE).minusSeconds(60));
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(stale));
        PosSaleRequest req = request(null);

        assertThatThrownBy(() -> service.processSale(null, req))
                .isInstanceOf(PosSaleFlowException.class)
                .extracting(e -> ((PosSaleFlowException) e).getStatus())
                .isEqualTo(PosSaleFlowStatus.STALE_REPLAY);
    }

    // =========================================================================
    // Cause 3 (NO CHEAP READ PATH): a definitive verdict without re-running the sale.
    // =========================================================================

    @Test
    void lookup_stampedKey_reportsPostedWithTheInvoiceReference() {
        SalesInvoiceDto posted = mock(SalesInvoiceDto.class);
        when(posted.uid()).thenReturn("INV-001");
        when(posted.invoiceNumber()).thenReturn("SI-000123");
        when(posted.grossTotalAmount()).thenReturn(new BigDecimal("2500.00"));
        when(invoiceService.getByUid("INV-001")).thenReturn(posted);
        PosSaleIdempotency marker = stampedMarker("INV-001");
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));

        var verdict = service.lookupByIdempotencyKey(IDEM_KEY);

        assertThat(verdict.verdict()).isEqualTo(PosSaleLookupVerdict.POSTED);
        assertThat(verdict.invoiceNumber()).isEqualTo("SI-000123");
        assertThat(verdict.grossTotalAmount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void lookup_unknownKey_reportsNeverPostedAndRunsNoBusinessGuard() {
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.empty());

        var verdict = service.lookupByIdempotencyKey(IDEM_KEY);

        assertThat(verdict.verdict()).isEqualTo(PosSaleLookupVerdict.NEVER_POSTED);
        assertThat(verdict.invoiceUid()).isNull();
        // The whole point: asking the question must never touch the session or the stock.
        verify(posSessionRepo, never()).findByUid(any());
        verify(invoiceService, never()).create(any());
    }

    @Test
    void lookup_reservedButUnstampedKey_reportsUnknownRatherThanGuessing() {
        PosSaleIdempotency marker = unstampedMarker(Instant.now());
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));

        assertThat(service.lookupByIdempotencyKey(IDEM_KEY).verdict())
                .isEqualTo(PosSaleLookupVerdict.UNKNOWN);
    }

    /** A closed session must not stop the question being answered — that was the whole bug. */
    @Test
    void lookup_worksAfterTheSessionClosed() {
        PosSession session = closedSession();
        PosSaleIdempotency marker = stampedMarker("INV-001");
        when(posSessionRepo.findByUid(SESSION_UID)).thenReturn(Optional.of(session));
        when(idempotency.findByCompanyIdAndIdemKey(COMPANY_ID, IDEM_KEY))
                .thenReturn(Optional.of(marker));

        assertThat(service.lookupByIdempotencyKey(IDEM_KEY).verdict())
                .isEqualTo(PosSaleLookupVerdict.POSTED);
    }

    /** Scope comes from the caller's active company, never from the request. */
    @Test
    void lookup_withNoActiveCompany_isRefused() {
        RequestContext.set(new RequestContext.Principal(
                USER_ID, "cashier", false, null, null, null));

        assertThatThrownBy(() -> service.lookupByIdempotencyKey(IDEM_KEY))
                .isInstanceOf(ForbiddenException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PosSession closedSession() {
        PosSession s = buildSession();
        when(s.getStatus()).thenReturn(PosSessionStatus.CLOSED);
        return s;
    }

    private PosSaleIdempotency stampedMarker(String invoiceUid) {
        PosSaleIdempotency m = mock(PosSaleIdempotency.class);
        when(m.getInvoiceUid()).thenReturn(invoiceUid);
        return m;
    }

    private PosSaleIdempotency unstampedMarker(Instant createdAt) {
        PosSaleIdempotency m = mock(PosSaleIdempotency.class);
        when(m.getInvoiceUid()).thenReturn(null);
        when(m.getCreatedAt()).thenReturn(createdAt);
        return m;
    }

    private PosSaleRequest requestCapturedAt(Instant capturedAt) {
        return new PosSaleRequest(
                SESSION_UID, CUSTOMER_ID, null, "TZS",
                List.of(new PosSaleRequest.LineItem(
                        PRODUCT_ID, UNIT_ID, BigDecimal.ONE, null, null)),
                null, BigDecimal.TEN, null, null, null, capturedAt);
    }

    private PosSaleRequest request(Boolean ageVerified) {
        return new PosSaleRequest(
                SESSION_UID, CUSTOMER_ID, null, "TZS",
                List.of(new PosSaleRequest.LineItem(
                        PRODUCT_ID, UNIT_ID, BigDecimal.ONE, null, null)),
                null, BigDecimal.TEN, null, ageVerified);
    }

    private Product product(RestrictedKind kind) {
        // Build a real Product — no mock() calls inside when(...) chains (Mockito strict rule).
        // UnitOfMeasure is pre-stubbed in setUp so getUid() is safe.
        UnitOfMeasure baseUnit = stubbedUnit();
        Product p = new Product(COMPANY_ID, "P001", "Beer 500ml", ProductType.GOODS,
                true, true, baseUnit, USER_ID);
        p.setRestrictedKind(kind);
        return p;
    }

    /** Returns a pre-stubbed UnitOfMeasure mock that is safe to use outside a when() chain. */
    private UnitOfMeasure stubbedUnit() {
        UnitOfMeasure u = mock(UnitOfMeasure.class);
        when(u.getUid()).thenReturn("UOM-001");
        return u;
    }

    private PosSession buildSession() {
        PosSession s = mock(PosSession.class);
        when(s.getCompanyId()).thenReturn(COMPANY_ID);
        when(s.getBranchId()).thenReturn(BRANCH_ID);
        when(s.getId()).thenReturn(99L);
        when(s.getUid()).thenReturn(SESSION_UID);
        when(s.getStatus()).thenReturn(PosSessionStatus.OPEN);
        return s;
    }
}
