package com.erp.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.notifications.domain.entity.NotificationScanMarker;
import com.erp.modules.notifications.repository.NotificationScanMarkerRepository;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link NotificationScanner} (ADR-0024 D-7 / finding #1 fix).
 *
 * <p>Key invariant under test: if {@link NotificationRaiser#raise} throws, the scan marker
 * must NOT be persisted so the next scan run can retry the same condition (BR-NOTIF-08).
 * Before the fix, {@code markers.save(marker)} was called before {@code raiser.raise()},
 * permanently suppressing retry on failure.
 */
class NotificationScannerTest {

    private CompanyRepository                companies;
    private ArInvoiceRepository              arInvoices;
    private StockOnHandRepository            stockOnHand;
    private NotificationScanMarkerRepository markers;
    private NotificationRaiser               raiser;
    private NotificationScanner             scanner;

    @BeforeEach
    void setUp() {
        companies   = mock(CompanyRepository.class);
        arInvoices  = mock(ArInvoiceRepository.class);
        stockOnHand = mock(StockOnHandRepository.class);
        markers     = mock(NotificationScanMarkerRepository.class);
        raiser      = mock(NotificationRaiser.class);
        scanner     = new NotificationScanner(companies, arInvoices, stockOnHand, markers, raiser);
    }

    // -------------------------------------------------------------------------
    // Finding #1 (HIGH): marker must NOT be saved when raiser.raise() throws
    // -------------------------------------------------------------------------

    /**
     * Before the fix: markers.save(marker) was called BEFORE raiser.raise(), so a raise failure
     * would persist a marker and permanently suppress retry on the next scan.
     * After the fix: markers.save() is only called inside the try block after raise() succeeds.
     */
    @Test
    void scanOverdue_raiseFails_markerNotPersisted_allowsRetryOnNextScan() {
        Long companyId = 1L;
        ArInvoice invoice = mockOverdueInvoice("INV-001-UID", companyId, 1L, LocalDate.now().minusDays(5));

        when(arInvoices.findOverdueByCompany(eq(companyId), any(LocalDate.class)))
                .thenReturn(List.of(invoice));
        // No existing marker for this invoice
        when(markers.findByCompanyIdAndTypeKeyAndConditionKey(
                companyId, NotificationScanner.TYPE_INVOICE_OVERDUE, "INV-001-UID"))
                .thenReturn(Optional.empty());

        // Simulate a transient failure in raise (e.g. DB error, template missing)
        doThrow(new RuntimeException("transient failure")).when(raiser).raise(any());

        // Must not throw — the scanner catches and logs
        scanner.scanOverdue(companyId);

        // CRITICAL: marker must NOT have been saved (no call to markers.save at all)
        verify(markers, never()).save(any(NotificationScanMarker.class));
    }

    /**
     * Happy path: when raise() succeeds the marker is persisted exactly once,
     * with recordNotified() called on it (so lastNotifiedAt and armed=false are set).
     */
    @Test
    void scanOverdue_raiseSucceeds_markerPersistedOnce() {
        Long companyId = 1L;
        ArInvoice invoice = mockOverdueInvoice("INV-002-UID", companyId, 1L, LocalDate.now().minusDays(3));

        when(arInvoices.findOverdueByCompany(eq(companyId), any(LocalDate.class)))
                .thenReturn(List.of(invoice));
        when(markers.findByCompanyIdAndTypeKeyAndConditionKey(
                companyId, NotificationScanner.TYPE_INVOICE_OVERDUE, "INV-002-UID"))
                .thenReturn(Optional.empty());
        when(markers.save(any(NotificationScanMarker.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scanner.scanOverdue(companyId);

        // Marker saved exactly once (after raise)
        ArgumentCaptor<NotificationScanMarker> captor =
                ArgumentCaptor.forClass(NotificationScanMarker.class);
        verify(markers).save(captor.capture());

        NotificationScanMarker saved = captor.getValue();
        assertThat(saved.getTypeKey()).isEqualTo(NotificationScanner.TYPE_INVOICE_OVERDUE);
        assertThat(saved.getConditionKey()).isEqualTo("INV-002-UID");
        // recordNotified() was called: lastNotifiedAt is non-null, armed=false
        assertThat(saved.getLastNotifiedAt()).isNotNull();
        assertThat(saved.isArmed()).isFalse();
    }

    /**
     * Existing marker means already-notified: skip this invoice, no raise, no additional save.
     */
    @Test
    void scanOverdue_existingMarker_invoiceSkipped() {
        Long companyId = 1L;
        ArInvoice invoice = mockOverdueInvoice("INV-003-UID", companyId, 1L, LocalDate.now().minusDays(1));

        when(arInvoices.findOverdueByCompany(eq(companyId), any(LocalDate.class)))
                .thenReturn(List.of(invoice));
        when(markers.findByCompanyIdAndTypeKeyAndConditionKey(
                companyId, NotificationScanner.TYPE_INVOICE_OVERDUE, "INV-003-UID"))
                .thenReturn(Optional.of(mock(NotificationScanMarker.class)));

        scanner.scanOverdue(companyId);

        verify(raiser, never()).raise(any());
        verify(markers, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Low-stock scanner: marker is already saved only after raise (no regression)
    // -------------------------------------------------------------------------

    @Test
    void scanLowStock_raiseFails_markerNotPersisted() {
        Long companyId = 1L;
        StockOnHand row = mockLowStockRow("SOH-001-UID", companyId, 1L,
                new BigDecimal("5"), new BigDecimal("10"));

        when(stockOnHand.findAboveReorderByCompany(companyId)).thenReturn(List.of());
        when(stockOnHand.findAtOrBelowReorderByCompany(companyId)).thenReturn(List.of(row));
        when(markers.findByCompanyIdAndTypeKeyAndConditionKey(
                companyId, NotificationScanner.TYPE_LOW_STOCK, "SOH-001-UID:1"))
                .thenReturn(Optional.empty());

        doThrow(new RuntimeException("transient failure")).when(raiser).raise(any());

        scanner.scanLowStock(companyId);

        // Marker save is skipped when raise throws
        verify(markers, never()).save(any(NotificationScanMarker.class));
    }

    @Test
    void scanLowStock_raiseSucceeds_markerSavedWithArmedFalse() {
        Long companyId = 1L;
        StockOnHand row = mockLowStockRow("SOH-002-UID", companyId, 1L,
                new BigDecimal("3"), new BigDecimal("10"));

        when(stockOnHand.findAboveReorderByCompany(companyId)).thenReturn(List.of());
        when(stockOnHand.findAtOrBelowReorderByCompany(companyId)).thenReturn(List.of(row));
        when(markers.findByCompanyIdAndTypeKeyAndConditionKey(
                companyId, NotificationScanner.TYPE_LOW_STOCK, "SOH-002-UID:1"))
                .thenReturn(Optional.empty());
        when(markers.save(any(NotificationScanMarker.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scanner.scanLowStock(companyId);

        ArgumentCaptor<NotificationScanMarker> captor =
                ArgumentCaptor.forClass(NotificationScanMarker.class);
        verify(markers).save(captor.capture());

        NotificationScanMarker saved = captor.getValue();
        assertThat(saved.getTypeKey()).isEqualTo(NotificationScanner.TYPE_LOW_STOCK);
        assertThat(saved.isArmed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ArInvoice mockOverdueInvoice(String uid, Long companyId, Long branchId,
                                          LocalDate dueDate) {
        ArInvoice inv = mock(ArInvoice.class);
        when(inv.getUid()).thenReturn(uid);
        when(inv.getCompanyId()).thenReturn(companyId);
        when(inv.getBranchId()).thenReturn(branchId);
        when(inv.getCustomerId()).thenReturn(99L);
        when(inv.getDueDate()).thenReturn(dueDate);
        when(inv.getDocumentNo()).thenReturn(uid);
        when(inv.getOutstandingAmount()).thenReturn(new BigDecimal("500.00"));
        when(inv.getCurrency()).thenReturn(CurrencyCode.of("TZS"));
        return inv;
    }

    private StockOnHand mockLowStockRow(String uid, Long companyId, Long branchId,
                                         BigDecimal qty, BigDecimal reorderLevel) {
        StockOnHand row = mock(StockOnHand.class);
        when(row.getUid()).thenReturn(uid);
        when(row.getCompanyId()).thenReturn(companyId);
        when(row.getBranchId()).thenReturn(branchId);
        when(row.getProductId()).thenReturn(42L);
        when(row.getQuantity()).thenReturn(qty);
        when(row.getReorderLevel()).thenReturn(reorderLevel);
        return row;
    }
}
