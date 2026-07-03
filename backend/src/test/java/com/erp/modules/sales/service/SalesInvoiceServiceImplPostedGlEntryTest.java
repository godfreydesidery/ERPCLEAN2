package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SalesInvoiceServiceImpl}'s read-time resolution of
 * {@code SalesInvoiceDto.postedGlEntryUid} — the "View Journal" link parity fix mirroring AP's
 * {@code SupplierBillDto.postedGlEntryUid} (interview round-2 finance papercut).
 *
 * <p>Keyed on {@link JournalSourceType#SALES} + the invoice uid (the same (sourceType, sourceRef)
 * pair {@code GLPostingSafeInvoker.postSaleInNewTx} posts under, and {@code SaleVoidingHandler}
 * looks up to reverse). Only the enrichment path is exercised here; the rest of
 * {@link SalesInvoiceServiceImpl} is covered elsewhere (IT suite).
 */
@ExtendWith(MockitoExtension.class)
class SalesInvoiceServiceImplPostedGlEntryTest {

    @Mock SalesInvoiceRepository invoices;
    @Mock com.erp.modules.sales.repository.SalesInvoiceLineRepository lines;
    @Mock com.erp.modules.sales.repository.SalesInvoicePaymentRepository payments;
    @Mock com.erp.modules.sales.repository.TaxRateRepository taxRates;
    @Mock com.erp.modules.parties.repository.CustomerRepository customers;
    @Mock com.erp.modules.parties.repository.AgentRepository agents;
    @Mock com.erp.modules.products.repository.ProductRepository products;
    @Mock com.erp.modules.products.repository.UnitOfMeasureRepository units;
    @Mock com.erp.modules.products.repository.ProductPriceRepository prices;
    @Mock com.erp.modules.products.repository.ProductBulkPackRepository bulkPacks;
    @Mock com.erp.modules.iam.repository.CompanyRepository companies;
    @Mock SalesInvoiceCodeGenerator codeGen;
    @Mock InvoiceTotalsCalculator totalsCalc;
    @Mock ScopeGuard scopeGuard;
    @Mock com.erp.platform.audit.AuditService audit;
    @Mock com.erp.platform.events.OutboxPublisher outbox;
    @Mock com.erp.modules.routes.service.RouteService routeService;
    @Mock com.erp.modules.routes.repository.RouteRepository routeRepository;
    @Mock com.erp.modules.ar.service.ArBalanceService arBalanceService;
    @Mock com.erp.platform.security.PermissionResolver permissionResolver;
    @Mock com.erp.platform.common.money.FxDocumentConverter fxConverter;
    @Mock com.erp.modules.parties.repository.PaymentTermsRepository paymentTermsRepo;
    @Mock JournalEntryRepository journalEntries;

    @InjectMocks SalesInvoiceServiceImpl service;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByUid_carriesMatchingJournalUid_whenInvoiceFinalised() {
        SalesInvoice inv = invoiceWithId(500L, "INVUID0000000000000000001",
                InvoiceStatus.FINALISED);
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        JournalEntry entry = mock(JournalEntry.class);
        when(entry.getUid()).thenReturn("JEUID0000000000000000009");
        when(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(
                COMPANY_ID, JournalSourceType.SALES, inv.getUid()))
                .thenReturn(Optional.of(entry));

        SalesInvoiceDto dto = service.getByUid(inv.getUid());

        assertThat(dto.postedGlEntryUid()).isEqualTo("JEUID0000000000000000009");
    }

    @Test
    void getByUid_postedGlEntryUidNull_whenInvoiceIsDraft() {
        SalesInvoice inv = invoiceWithId(501L, "INVUID0000000000000000002",
                InvoiceStatus.DRAFT);
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));

        SalesInvoiceDto dto = service.getByUid(inv.getUid());

        assertThat(dto.postedGlEntryUid()).isNull();
        // DRAFT invoices never have a posted journal — the repository must not even be queried.
        verifyNoInteractions(journalEntries);
    }

    @Test
    void getByUid_postedGlEntryUidNull_whenFinalisedButNoMatchingJournalFound() {
        SalesInvoice inv = invoiceWithId(502L, "INVUID0000000000000000003",
                InvoiceStatus.FINALISED);
        when(invoices.findByUid(inv.getUid())).thenReturn(Optional.of(inv));
        when(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(
                COMPANY_ID, JournalSourceType.SALES, inv.getUid()))
                .thenReturn(Optional.empty());

        SalesInvoiceDto dto = service.getByUid(inv.getUid());

        assertThat(dto.postedGlEntryUid()).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SalesInvoice invoiceWithId(Long id, String uid, InvoiceStatus status) {
        SalesInvoice inv = new SalesInvoice(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID,
                "TZS", 1L);
        ReflectionTestUtils.setField(inv, "id", id);
        ReflectionTestUtils.setField(inv, "uid", uid);
        inv.setStatus(status);
        return inv;
    }
}
