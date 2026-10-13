package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApDebitNoteAllocationRepository;
import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AP-01: a held or never-matched bill can be deleted (so the invoice can be entered again), and a
 * bill that touched the books cannot. Runs as a non-root clerk.
 */
class SupplierBillCorrectionServiceImplTest {

    private static final Long COMPANY_ID = 10L;

    private SupplierBillRepository          bills;
    private SupplierBillLineRepository      lines;
    private BillMatchRepository             matches;
    private ApPaymentAllocationRepository   paymentAllocations;
    private ApDebitNoteRepository           debitNotes;
    private ApDebitNoteAllocationRepository debitNoteAllocations;
    private JdbcTemplate                    jdbc;
    private AuditService                    audit;
    private SupplierBillCorrectionServiceImpl service;

    @BeforeEach
    void setUp() {
        bills                = mock(SupplierBillRepository.class);
        lines                = mock(SupplierBillLineRepository.class);
        matches              = mock(BillMatchRepository.class);
        paymentAllocations   = mock(ApPaymentAllocationRepository.class);
        debitNotes           = mock(ApDebitNoteRepository.class);
        debitNoteAllocations = mock(ApDebitNoteAllocationRepository.class);
        jdbc                 = mock(JdbcTemplate.class);
        audit                = mock(AuditService.class);
        // Unstubbed COUNT(*) reads answer null — treated as "no journal entry / no landed cost".
        service = new SupplierBillCorrectionServiceImpl(bills, lines, matches, paymentAllocations,
                debitNotes, debitNoteAllocations, jdbc, mock(ScopeGuard.class), audit);
        RequestContext.set(new RequestContext.Principal(
                7L, "amina.accounts", false, COMPANY_ID, 20L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("A HELD bill with nothing against it is deleted with its lines and match rows")
    void deleteUnposted_heldBill_deletesBillLinesAndMatches() {
        SupplierBill bill = bill(SupplierBillStatus.HELD);
        bill.setBillNumber("BILL-0007");
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));

        service.deleteUnposted("bill-1");

        verify(matches).deleteAll(any());
        verify(lines).deleteAll(any());
        verify(bills).delete(bill);
        verify(audit).record(any());
    }

    @Test
    @DisplayName("A DRAFT bill (match never ran or failed) is deleted")
    void deleteUnposted_draftBill_deletes() {
        SupplierBill bill = bill(SupplierBillStatus.DRAFT);
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));

        service.deleteUnposted("bill-1");

        verify(bills).delete(bill);
    }

    @Test
    @DisplayName("A MATCHED (posted) bill cannot be deleted")
    void deleteUnposted_matchedBill_refuses() {
        SupplierBill bill = bill(SupplierBillStatus.MATCHED);
        bill.setBillNumber("BILL-0008");
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));

        assertThatThrownBy(() -> service.deleteUnposted("bill-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("debit note");
        verify(bills, never()).delete(any());
    }

    @Test
    @DisplayName("A held bill that somehow carries a GL posting cannot be deleted")
    void deleteUnposted_heldBillWithGlPosting_refuses() {
        SupplierBill bill = bill(SupplierBillStatus.HELD);
        bill.setBillNumber("BILL-0009");
        bill.setPostedGlEntryUid("je-1");
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));

        assertThatThrownBy(() -> service.deleteUnposted("bill-1"))
                .isInstanceOf(ConflictException.class);
        verify(bills, never()).delete(any());
    }

    @Test
    @DisplayName("A bill with a payment allocation cannot be deleted")
    void deleteUnposted_paidBill_refuses() {
        SupplierBill bill = bill(SupplierBillStatus.HELD);
        bill.setBillNumber("BILL-0010");
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));
        when(paymentAllocations.findBySupplierBillId(any()))
                .thenReturn(List.of(mock(ApPaymentAllocation.class)));

        assertThatThrownBy(() -> service.deleteUnposted("bill-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("payment");
        verify(bills, never()).delete(any());
    }

    @Test
    @DisplayName("A bill a debit note was raised against cannot be deleted")
    void deleteUnposted_billWithDebitNote_refuses() {
        SupplierBill bill = bill(SupplierBillStatus.HELD);
        bill.setBillNumber("BILL-0011");
        when(bills.findByUid("bill-1")).thenReturn(Optional.of(bill));
        when(debitNotes.existsBySupplierBillId(any())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteUnposted("bill-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("debit note");
        verify(bills, never()).delete(any());
    }

    private static SupplierBill bill(SupplierBillStatus status) {
        SupplierBill b = new SupplierBill(COMPANY_ID, 20L, 5L, "INV-1", SupplierBillSource.BILL,
                null, LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), "TZS", 1L);
        b.setStatus(status);
        return b;
    }
}
