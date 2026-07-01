package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.service.ApDebitNoteService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.dto.PurchaseReturnedPayload;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseReturn;
import com.erp.modules.purchases.domain.entity.PurchaseReturnLine;
import com.erp.modules.purchases.domain.enums.PurchaseReturnStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.PurchaseReturnLineRepository;
import com.erp.modules.purchases.repository.PurchaseReturnRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for PurchaseReturnServiceImpl.
 *
 * <p>Proves fixes for adversarial-review findings:
 * <ul>
 *   <li>BLOCKER/HIGH (2+3): confirm() raises AP debit note synchronously and sets debitNoteUid.
 *   <li>HIGH (4): payload carries billed field.
 *   <li>MEDIUM (7): confirm() re-validates returned qty before updating GR line (concurrent race guard).
 * </ul>
 */
class PurchaseReturnServiceImplTest {

    private PurchaseReturnRepository     returns;
    private PurchaseReturnLineRepository returnLines;
    private GoodsReceiptRepository       grRepo;
    private GoodsReceiptLineRepository   grLineRepo;
    private PurchaseOrderRepository      poRepo;
    private CompanyRepository            companies;
    private SupplierRepository           suppliers;
    private ApDebitNoteService           apDebitNoteService;
    private PurchaseNumberGenerator      numberGen;
    private OutboxPublisher              outbox;
    private ScopeGuard                   scopeGuard;
    private AuditService                 audit;

    private PurchaseReturnServiceImpl service;

    @BeforeEach
    void setUp() {
        returns            = mock(PurchaseReturnRepository.class);
        returnLines        = mock(PurchaseReturnLineRepository.class);
        grRepo             = mock(GoodsReceiptRepository.class);
        grLineRepo         = mock(GoodsReceiptLineRepository.class);
        poRepo             = mock(PurchaseOrderRepository.class);
        companies          = mock(CompanyRepository.class);
        suppliers          = mock(SupplierRepository.class);
        apDebitNoteService = mock(ApDebitNoteService.class);
        numberGen          = mock(PurchaseNumberGenerator.class);
        outbox             = mock(OutboxPublisher.class);
        scopeGuard         = mock(ScopeGuard.class);
        audit              = mock(AuditService.class);

        service = new PurchaseReturnServiceImpl(
                returns, returnLines, grRepo, grLineRepo, poRepo,
                companies, suppliers, apDebitNoteService,
                numberGen, outbox, scopeGuard, audit);

        // Default principal in context
        RequestContext.set(new RequestContext.Principal(1L, "user@test.com", false, 10L, 20L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Fix 2+3 (BLOCKER/HIGH): confirm() MUST raise AP debit note synchronously
    // -------------------------------------------------------------------------

    @Test
    void confirm_raisesApDebitNoteSynchronouslyAndSetsDebitNoteUid() {
        // arrange
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-1", 10L, 20L, 50L);

        PurchaseReturnLine line = stubReturnLine(1L, "GRL-UID-1", 1L,
                new BigDecimal("10.00"), new BigDecimal("100.00"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("20.00"), BigDecimal.ZERO);
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));

        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMP-UID-1");
        when(companies.findById(10L)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getUid()).thenReturn("SUPP-UID-1");
        when(suppliers.findById(50L)).thenReturn(Optional.of(supplier));

        ApDebitNoteDto debitNoteDto = stubDebitNoteDto("DN-UID-1", "DN-0001");
        when(apDebitNoteService.raise(any())).thenReturn(debitNoteDto);

        // act
        service.confirm("PRET-UID-1");

        // assert: AP debit note raised exactly once
        ArgumentCaptor<RaiseDebitNoteRequest> captor = forClass(RaiseDebitNoteRequest.class);
        verify(apDebitNoteService).raise(captor.capture());
        RaiseDebitNoteRequest req = captor.getValue();

        assertThat(req.companyUid()).isEqualTo("COMP-UID-1");
        assertThat(req.supplierUid()).isEqualTo("SUPP-UID-1");
        assertThat(req.netAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        // Bug #11 fix: origin must be exactly "PURCHASE_RETURN" — the ap_debit_notes CHECK
        // constraint only allows 'STANDALONE' or 'PURCHASE_RETURN' (no colon+uid suffix).
        assertThat(req.origin()).isEqualTo("PURCHASE_RETURN");

        // assert: debitNoteUid set on the return header
        verify(ret).setDebitNoteUid("DN-UID-1");
    }

    @Test
    void confirm_zeroReturnValue_doesNotRaiseDebitNote() {
        // A return with zero total (edge case) must not raise a debit note
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-ZERO", 10L, 20L, 50L);

        PurchaseReturnLine line = stubReturnLine(1L, "GRL-UID-Z", 1L,
                new BigDecimal("0.00"), new BigDecimal("0.00"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("10.00"), BigDecimal.ZERO);
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));

        // act
        service.confirm("PRET-UID-ZERO");

        // assert: no debit note raised for zero total
        verify(apDebitNoteService, org.mockito.Mockito.never()).raise(any());
    }

    // -------------------------------------------------------------------------
    // Fix 4 (HIGH): payload must carry billed field
    // -------------------------------------------------------------------------

    @Test
    void confirm_publishedPayloadContainsBilledFlag() {
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-B", 10L, 20L, 50L);

        PurchaseReturnLine line = stubReturnLine(1L, "GRL-UID-B", 1L,
                new BigDecimal("5.00"), new BigDecimal("50.00"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("20.00"), BigDecimal.ZERO);
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));

        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMP-UID-B");
        when(companies.findById(10L)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getUid()).thenReturn("SUPP-UID-B");
        when(suppliers.findById(50L)).thenReturn(Optional.of(supplier));

        when(apDebitNoteService.raise(any())).thenReturn(stubDebitNoteDto("DN-UID-B", "DN-0002"));

        // capture the outbox publish call to inspect the payload
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        // act
        service.confirm("PRET-UID-B");

        // assert: outbox was called (payload serialised via Jackson, tested in handler tests)
        verify(outbox).publish(any(), any(), any(), any(), any(), any(), payloadCaptor.capture());
        Object publishedPayload = payloadCaptor.getValue();
        assertThat(publishedPayload).isInstanceOf(PurchaseReturnedPayload.class);
        PurchaseReturnedPayload payload = (PurchaseReturnedPayload) publishedPayload;
        // billed field present (spec: ADR-0027 D-7 line 255); conservative default is false
        assertThat(payload.billed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Fix 7 (MEDIUM): confirm() re-validates qty against GR line before update (BR-PROC-10)
    // -------------------------------------------------------------------------

    @Test
    void confirm_concurrentOverReturn_throwsBeforeUpdate() {
        // Simulate: return line has returnedQtyInBase=10 but GR line already has
        // returnedQtyInBase=18 (qty_in_base=20) from a concurrent confirm — would exceed 20.
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-RACE", 10L, 20L, 50L);

        PurchaseReturnLine line = stubReturnLine(1L, "GRL-UID-RACE", 1L,
                new BigDecimal("5.00"), new BigDecimal("50.00"));
        // returned_qty_in_base = 10 on this line
        when(line.getReturnedQtyInBase()).thenReturn(new BigDecimal("10.00"));

        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        // GR line: qty_in_base=20, already returned=18 (concurrent confirm updated it)
        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("20.00"), new BigDecimal("18.00"));
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));

        // act + assert: should throw before saving the GR line (BR-PROC-10 guard)
        assertThatThrownBy(() -> service.confirm("PRET-UID-RACE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed the original receipted quantity");

        // assert: GR line was NOT saved (no partial state written)
        verify(grLineRepo, org.mockito.Mockito.never()).save(grLine);
    }

    @Test
    void confirm_exactQtyReturn_updatesGrLineCorrectly() {
        // Returning exactly the remaining qty — must succeed
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-EXACT", 10L, 20L, 50L);

        PurchaseReturnLine line = stubReturnLine(1L, "GRL-UID-EXACT", 1L,
                new BigDecimal("5.00"), new BigDecimal("50.00"));
        when(line.getReturnedQtyInBase()).thenReturn(new BigDecimal("2.00"));

        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        // GR line: qty_in_base=10, already returned=8 → remaining=2
        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("10.00"), new BigDecimal("8.00"));
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));

        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMP-UID-EXACT");
        when(companies.findById(10L)).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getUid()).thenReturn("SUPP-UID-EXACT");
        when(suppliers.findById(50L)).thenReturn(Optional.of(supplier));

        when(apDebitNoteService.raise(any())).thenReturn(stubDebitNoteDto("DN-EXACT", "DN-0003"));

        // act — must not throw
        service.confirm("PRET-UID-EXACT");

        // assert: GR line saved with updated qty (8 + 2 = 10)
        verify(grLineRepo).save(grLine);
        verify(grLine).setReturnedQtyInBase(new BigDecimal("10.00"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PurchaseReturn stubConfirmableReturn(String uid, Long companyId, Long branchId,
                                                  Long supplierId) {
        PurchaseReturn ret = mock(PurchaseReturn.class);
        when(ret.getUid()).thenReturn(uid);
        when(ret.getId()).thenReturn(99L);
        when(ret.getCompanyId()).thenReturn(companyId);
        when(ret.getBranchId()).thenReturn(branchId);
        when(ret.getSupplierId()).thenReturn(supplierId);
        when(ret.getStatus()).thenReturn(PurchaseReturnStatus.DRAFT);
        when(ret.getReturnNumber()).thenReturn("PRET-0001");
        when(ret.getReason()).thenReturn("Defective goods");
        when(returns.findByUid(uid)).thenReturn(Optional.of(ret));
        when(returns.save(ret)).thenReturn(ret);
        return ret;
    }

    private PurchaseReturnLine stubReturnLine(Long grLineId, String grLineUid,
                                               Long productId,
                                               BigDecimal unitCost, BigDecimal lineValue) {
        PurchaseReturnLine l = mock(PurchaseReturnLine.class);
        when(l.getGoodsReceiptLineId()).thenReturn(grLineId);
        when(l.getGoodsReceiptLineUid()).thenReturn(grLineUid);
        when(l.getProductId()).thenReturn(productId);
        when(l.getReturnedQtyInBase()).thenReturn(new BigDecimal("10.00"));
        when(l.getUnitCostAmount()).thenReturn(unitCost);
        when(l.getLineValueAmount()).thenReturn(lineValue);
        return l;
    }

    private GoodsReceiptLine stubGrLine(Long id, BigDecimal qtyInBase, BigDecimal alreadyReturned) {
        GoodsReceiptLine g = mock(GoodsReceiptLine.class);
        when(g.getId()).thenReturn(id);
        when(g.getQtyInBase()).thenReturn(qtyInBase);
        when(g.getReturnedQtyInBase()).thenReturn(alreadyReturned);
        return g;
    }

    private ApDebitNoteDto stubDebitNoteDto(String uid, String number) {
        return new ApDebitNoteDto(
                1L, uid, 10L, 20L, 50L, number, null,
                LocalDate.now(),
                new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO,
                "TZS", "Purchase return test", null, "PURCHASE_RETURN:PRET-UID-1",
                null,  // P2: originRef (uid suffix) — not split in this stub
                // ADR-0041 D3: unapplied tracking + allocations (debit-note parity)
                BigDecimal.ZERO,            // unappliedAmount
                new BigDecimal("100.00"),  // baseAmount
                BigDecimal.ZERO,            // baseUnappliedAmount
                BigDecimal.ONE,             // fxRate
                com.erp.modules.ap.domain.enums.ApDebitNoteStatus.APPLIED,
                java.util.List.of());       // allocations
    }
}
