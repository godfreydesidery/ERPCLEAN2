package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.dto.FiscalReceiptDto;
import com.erp.modules.sales.domain.entity.FiscalReceipt;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.enums.FiscalReceiptStatus;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.FiscalReceiptRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.fiscal.FiscalInvoiceDataDto;
import com.erp.platform.fiscal.FiscalisationOutcome;
import com.erp.platform.fiscal.FiscalisationProvider;
import com.erp.platform.fiscal.FiscalisationResult;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link FiscalReceiptServiceImpl} (ADR-0049 §4). All repositories + the provider
 * are mocked; the HTTP-level / real-Postgres coverage (permission gates, cross-company 403, the
 * default-provider NOT_CONFIGURED end-to-end path) lives in {@code FiscalReceiptIT}.
 */
@ExtendWith(MockitoExtension.class)
class FiscalReceiptServiceImplTest {

    @Mock FiscalReceiptRepository fiscalReceipts;
    @Mock SalesInvoiceRepository invoices;
    @Mock SalesInvoiceLineRepository lines;
    @Mock CompanyRepository companies;
    @Mock CustomerRepository customers;
    @Mock FiscalisationProvider provider;
    @Mock ScopeGuard scopeGuard;
    @Mock AuditService audit;

    @InjectMocks FiscalReceiptServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;
    private static final String INVOICE_UID = "INVUID0000000000000000010";

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Principal(
                1L, "tester", true, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // issue — no existing row, default provider → NOT_CONFIGURED
    // -------------------------------------------------------------------------

    @Test
    void issue_defaultProvider_returnsNotConfigured() {
        SalesInvoice inv = finalisedInvoice(500L, INVOICE_UID);
        when(invoices.findByUid(INVOICE_UID)).thenReturn(Optional.of(inv));
        when(fiscalReceipts.findBySalesInvoiceId(500L)).thenReturn(Optional.empty());
        when(fiscalReceipts.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInvoiceDataLookups(inv);

        when(provider.providerCode()).thenReturn("NOT_CONFIGURED");
        when(provider.fiscalise(any())).thenReturn(
                FiscalisationResult.notConfigured("No fiscal device is configured."));

        FiscalReceiptDto dto = service.issue(INVOICE_UID);

        assertThat(dto.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(dto.invoiceUid()).isEqualTo(INVOICE_UID);
        assertThat(dto.fiscalNumber()).isNull();
        assertThat(dto.attemptCount()).isEqualTo(1);
        assertThat(dto.providerCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(dto.errorDetail()).isNotBlank();
        // Created once (reserve the row) + saved again after the outcome is applied.
        verify(fiscalReceipts, times(2)).save(any());
    }

    @Test
    void issue_buildsInvoiceDataFromInvoiceLinesAndCompanyAndCustomer() {
        SalesInvoice inv = finalisedInvoice(501L, "INVUID0000000000000000011");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));
        when(fiscalReceipts.findBySalesInvoiceId(501L)).thenReturn(Optional.empty());
        when(fiscalReceipts.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInvoiceDataLookups(inv);
        when(provider.providerCode()).thenReturn("NOT_CONFIGURED");
        when(provider.fiscalise(any())).thenReturn(FiscalisationResult.notConfigured("no device"));

        service.issue(inv.getUid());

        ArgumentCaptor<FiscalInvoiceDataDto> captor = ArgumentCaptor.forClass(FiscalInvoiceDataDto.class);
        verify(provider).fiscalise(captor.capture());
        FiscalInvoiceDataDto data = captor.getValue();

        assertThat(data.invoiceUid()).isEqualTo(inv.getUid());
        assertThat(data.invoiceNumber()).isEqualTo("INV-0001");
        assertThat(data.companyTin()).isEqualTo("TIN-001");
        assertThat(data.companyVrn()).isEqualTo("VRN-001");
        assertThat(data.customerName()).isEqualTo("Test Customer");
        assertThat(data.customerTin()).isNull(); // deferred, ADR-0049 §8.9
        assertThat(data.currency()).isEqualTo("TZS");
        assertThat(data.netTotal()).isEqualByComparingTo("1000");
        assertThat(data.vatTotal()).isEqualByComparingTo("180");
        assertThat(data.grossTotal()).isEqualByComparingTo("1180");
        assertThat(data.lines()).hasSize(1);
        FiscalInvoiceDataDto.Line line = data.lines().get(0);
        assertThat(line.description()).isEqualTo("Widget");
        assertThat(line.quantity()).isEqualByComparingTo("1");
        assertThat(line.unitCode()).isEqualTo("PCS");
        assertThat(line.unitPrice()).isEqualByComparingTo("1000");
        assertThat(line.lineNet()).isEqualByComparingTo("1000");
        assertThat(line.vatRatePercent()).isEqualByComparingTo("18.00");
        assertThat(line.lineVat()).isEqualByComparingTo("180");
        assertThat(line.taxCode()).isEqualTo("STANDARD");
    }

    // -------------------------------------------------------------------------
    // issue — a DRAFT invoice cannot be fiscalised (409)
    // -------------------------------------------------------------------------

    @Test
    void issue_draftInvoice_throwsIllegalState409() {
        SalesInvoice inv = draftInvoice(600L, "INVUID0000000000000000020");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.issue(inv.getUid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalised");

        verify(fiscalReceipts, never()).save(any());
        verify(provider, never()).fiscalise(any());
    }

    // -------------------------------------------------------------------------
    // issue — an ISSUED row is an idempotent no-op (never re-fiscalised)
    // -------------------------------------------------------------------------

    @Test
    void issue_alreadyIssued_isIdempotentNoOp() {
        SalesInvoice inv = finalisedInvoice(700L, "INVUID0000000000000000030");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        FiscalReceipt existing = new FiscalReceipt(COMPANY_ID, BRANCH_ID, 700L, 1L);
        ReflectionTestUtils.setField(existing, "uid", "FRUID000000000000000000001");
        existing.recordIssued("SIMULATED", "SIM-0000000001", "https://example.test/verify/1",
                "sig", "DEV-1", Instant.now(), Instant.now(), 1L);
        when(fiscalReceipts.findBySalesInvoiceId(700L)).thenReturn(Optional.of(existing));

        FiscalReceiptDto dto = service.issue(inv.getUid());

        assertThat(dto.status()).isEqualTo("ISSUED");
        assertThat(dto.fiscalNumber()).isEqualTo("SIM-0000000001");
        verify(provider, never()).fiscalise(any());
        verify(fiscalReceipts, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // issue — retry a FAILED row in place: same row, attemptCount++, version bumps naturally
    // -------------------------------------------------------------------------

    @Test
    void issue_retryFailedRow_sameRowInPlace_attemptCountIncrements() {
        SalesInvoice inv = finalisedInvoice(800L, "INVUID0000000000000000040");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        FiscalReceipt existing = new FiscalReceipt(COMPANY_ID, BRANCH_ID, 800L, 1L);
        ReflectionTestUtils.setField(existing, "uid", "FRUID000000000000000000002");
        existing.recordFailed("NOT_CONFIGURED", "previous failure", Instant.now(), 1L);
        assertThat(existing.getAttemptCount()).isEqualTo(1);

        when(fiscalReceipts.findBySalesInvoiceId(800L)).thenReturn(Optional.of(existing));
        when(fiscalReceipts.save(any())).thenAnswer(a -> a.getArgument(0));
        stubInvoiceDataLookups(inv);

        when(provider.providerCode()).thenReturn("SIMULATED");
        when(provider.fiscalise(any())).thenReturn(FiscalisationResult.issued(
                "SIMULATED", "SIM-0000000002", "https://example.test/verify/2", "sig2", "DEV-2", null));

        FiscalReceiptDto dto = service.issue(inv.getUid());

        assertThat(dto.uid()).isEqualTo("FRUID000000000000000000002"); // same row, not a new one
        assertThat(dto.status()).isEqualTo("ISSUED");
        assertThat(dto.attemptCount()).isEqualTo(2); // incremented from 1
        assertThat(dto.fiscalNumber()).isEqualTo("SIM-0000000002");

        // Only ONE save call: retry-in-place never creates a second row.
        verify(fiscalReceipts, times(1)).save(existing);
    }

    // -------------------------------------------------------------------------
    // issue — a VOID row is blocked (409 Conflict)
    // -------------------------------------------------------------------------

    @Test
    void issue_voidRow_throwsConflict409() {
        SalesInvoice inv = finalisedInvoice(900L, "INVUID0000000000000000050");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        FiscalReceipt existing = new FiscalReceipt(COMPANY_ID, BRANCH_ID, 900L, 1L);
        ReflectionTestUtils.setField(existing, "status", FiscalReceiptStatus.VOID);
        when(fiscalReceipts.findBySalesInvoiceId(900L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.issue(inv.getUid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("voided");

        verify(provider, never()).fiscalise(any());
        verify(fiscalReceipts, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // issue — scope assert path: a cross-company caller is rejected by ScopeGuard
    // -------------------------------------------------------------------------

    @Test
    void issue_scopeGuardRejects_propagatesForbidden() {
        SalesInvoice inv = finalisedInvoice(1000L, "INVUID0000000000000000060");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));
        doThrow(ForbiddenException.notPermitted())
                .when(scopeGuard).assertCanActIn(any(), any());

        assertThatThrownBy(() -> service.issue(inv.getUid()))
                .isInstanceOf(ForbiddenException.class);

        verify(fiscalReceipts, never()).findBySalesInvoiceId(any());
        verify(provider, never()).fiscalise(any());
    }

    // -------------------------------------------------------------------------
    // getForInvoice
    // -------------------------------------------------------------------------

    @Test
    void getForInvoice_noRow_throwsNotFound() {
        SalesInvoice inv = finalisedInvoice(1100L, "INVUID0000000000000000070");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));
        when(fiscalReceipts.findBySalesInvoiceId(1100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForInvoice(inv.getUid()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getForInvoice_existingRow_returnsDto() {
        SalesInvoice inv = finalisedInvoice(1200L, "INVUID0000000000000000080");
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        FiscalReceipt existing = new FiscalReceipt(COMPANY_ID, BRANCH_ID, 1200L, 1L);
        ReflectionTestUtils.setField(existing, "uid", "FRUID000000000000000000003");
        when(fiscalReceipts.findBySalesInvoiceId(1200L)).thenReturn(Optional.of(existing));

        FiscalReceiptDto dto = service.getForInvoice(inv.getUid());

        assertThat(dto.uid()).isEqualTo("FRUID000000000000000000003");
        assertThat(dto.invoiceUid()).isEqualTo(inv.getUid());
        assertThat(dto.status()).isEqualTo("PENDING");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubInvoiceDataLookups(SalesInvoice inv) {
        Company company = new Company(null, "CO1", "Test Co");
        company.setTaxId("TIN-001");
        company.setVrn("VRN-001");
        when(companies.findScopedById(inv.getCompanyId())).thenReturn(Optional.of(company));

        Customer customer = new Customer(inv.getCompanyId(), "CUST-0001",
                PartyType.INDIVIDUAL, "Test Customer", CustomerKind.CASH_WALK_IN, 1L);
        when(customers.findByCompanyIdAndId(inv.getCompanyId(), inv.getCustomerId()))
                .thenReturn(Optional.of(customer));

        SalesInvoiceLine line = new SalesInvoiceLine(inv, (short) 1,
                900L, "PROD-0001", "Widget",
                910L, "PCS",
                BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("1000"),
                com.erp.modules.products.domain.enums.VatStatus.STANDARD, new BigDecimal("0.1800"),
                1L);
        line.setNetAmount(new BigDecimal("1000"));
        line.setVatAmount(new BigDecimal("180"));
        line.setGrossAmount(new BigDecimal("1180"));
        when(lines.findByInvoiceIdOrderByLineNo(inv.getId())).thenReturn(List.of(line));
    }

    private static SalesInvoice finalisedInvoice(Long id, String uid) {
        SalesInvoice inv = new SalesInvoice(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS", 1L);
        ReflectionTestUtils.setField(inv, "id", id);
        ReflectionTestUtils.setField(inv, "uid", uid);
        inv.setInvoiceNumber("INV-0001");
        inv.setStatus(InvoiceStatus.FINALISED);
        inv.setFinalisedAt(Instant.now());
        ReflectionTestUtils.setField(inv, "netTotalAmount", new BigDecimal("1000"));
        ReflectionTestUtils.setField(inv, "vatTotalAmount", new BigDecimal("180"));
        ReflectionTestUtils.setField(inv, "grossTotalAmount", new BigDecimal("1180"));
        return inv;
    }

    private static SalesInvoice draftInvoice(Long id, String uid) {
        SalesInvoice inv = new SalesInvoice(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS", 1L);
        ReflectionTestUtils.setField(inv, "id", id);
        ReflectionTestUtils.setField(inv, "uid", uid);
        return inv;
    }
}
