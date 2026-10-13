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
import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.PurchaseReturnedPayload;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseReturn;
import com.erp.modules.purchases.domain.entity.PurchaseReturnLine;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
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
                numberGen, outbox, scopeGuard, audit, mock(PurchaseReturnPrintQuery.class),
                mock(com.erp.platform.common.money.FxDocumentConverter.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(com.erp.modules.purchases.domain.dto.ReceiptBillingReader.class),
                com.erp.platform.common.time.CompanyCalendar.fixed(
                        com.erp.platform.common.time.BusinessZone.DEFAULT, java.time.Clock.systemUTC()));

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
        when(apDebitNoteService.raiseForPurchaseReturn(any(), any(), any())).thenReturn(debitNoteDto);

        // act
        service.confirm("PRET-UID-1");

        // assert: AP debit note raised exactly once
        ArgumentCaptor<RaiseDebitNoteRequest> captor = forClass(RaiseDebitNoteRequest.class);
        verify(apDebitNoteService).raiseForPurchaseReturn(captor.capture(), any(), any());
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
        verify(apDebitNoteService, org.mockito.Mockito.never()).raiseForPurchaseReturn(any(), any(), any());
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

        when(apDebitNoteService.raiseForPurchaseReturn(any(), any(), any())).thenReturn(stubDebitNoteDto("DN-UID-B", "DN-0002"));

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
        when(line.getReturnedQty()).thenReturn(new BigDecimal("10.00"));

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
        when(line.getReturnedQty()).thenReturn(new BigDecimal("2.00"));

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

        when(apDebitNoteService.raiseForPurchaseReturn(any(), any(), any())).thenReturn(stubDebitNoteDto("DN-EXACT", "DN-0003"));

        // act — must not throw
        service.confirm("PRET-UID-EXACT");

        // assert: GR line saved with updated qty (8 + 2 = 10)
        verify(grLineRepo).save(grLine);
        verify(grLine).setReturnedQtyInBase(new BigDecimal("10.00"));
    }

    // -------------------------------------------------------------------------
    // PUR-02 / LBO-01 / LBO-02: returned qty is in the receipt LINE's unit
    // -------------------------------------------------------------------------

    @Test
    void create_onACrateLine_convertsTheReturnToBaseAndValuesItAtTheReceiptCost() {
        // GRN line: 8 crates of 25 bottles = 200 bottles, 360,000 spent (45,000 per crate).
        stubCreatableReceipt();
        stubPackGrLine(new BigDecimal("8"), new BigDecimal("200"),
                new BigDecimal("45000"), new BigDecimal("360000"));
        when(returnLines.sumReturnedQtyInBaseForGrLine(7L)).thenReturn(BigDecimal.ZERO);

        service.create(createRequest("1"));

        ArgumentCaptor<PurchaseReturnLine> saved = forClass(PurchaseReturnLine.class);
        verify(returnLines).save(saved.capture());
        PurchaseReturnLine line = saved.getValue();
        assertThat(line.getReturnedQty()).as("entered in crates").isEqualByComparingTo("1");
        assertThat(line.getReturnedQtyInBase())
                .as("LBO-01: 1 crate is 25 bottles leaving stock, not 1")
                .isEqualByComparingTo("25");
        assertThat(line.getLineValueAmount())
                .as("valued at the receipt's cost of those 25 bottles")
                .isEqualByComparingTo("45000");
        assertThat(line.getUnitName()).isEqualTo("Crate");
    }

    @Test
    void create_overReturnInCrates_isRejectedAgainstTheBaseRemainder() {
        stubCreatableReceipt();
        stubPackGrLine(new BigDecimal("8"), new BigDecimal("200"),
                new BigDecimal("45000"), new BigDecimal("360000"));
        when(returnLines.sumReturnedQtyInBaseForGrLine(7L)).thenReturn(BigDecimal.ZERO);

        // LBO-02: 150 crates used to pass because 150 < 200 (bottles).
        assertThatThrownBy(() -> service.create(createRequest("150")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You can return at most 8 Crate of Safari Lager on this receipt.");
        verify(returnLines, org.mockito.Mockito.never()).save(any(PurchaseReturnLine.class));
    }

    @Test
    void create_remainderIsReportedInTheLineUnitAfterEarlierReturns() {
        stubCreatableReceipt();
        stubPackGrLine(new BigDecimal("8"), new BigDecimal("200"),
                new BigDecimal("45000"), new BigDecimal("360000"));
        // 3 crates (75 bottles) already returned and confirmed.
        when(returnLines.sumReturnedQtyInBaseForGrLine(7L)).thenReturn(new BigDecimal("75"));

        assertThatThrownBy(() -> service.create(createRequest("6")))
                .hasMessage("You can return at most 5 Crate of Safari Lager on this receipt.");
    }

    @Test
    void confirm_sendsThePerBaseUnitCostToTheStockLedger() {
        stubConfirmableReturn("PRET-UID-PACK", 10L, 20L, 50L);
        PurchaseReturnLine line = stubReturnLine(7L, "GRL-PACK", 1L,
                new BigDecimal("45000"), new BigDecimal("45000"));
        when(line.getReturnedQty()).thenReturn(new BigDecimal("1"));
        when(line.getReturnedQtyInBase()).thenReturn(new BigDecimal("25"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        GoodsReceiptLine grLine = mock(GoodsReceiptLine.class);
        when(grLine.getId()).thenReturn(7L);
        when(grLine.getReceivedQty()).thenReturn(new BigDecimal("8"));
        when(grLine.getQtyInBase()).thenReturn(new BigDecimal("200"));
        when(grLine.getReturnedQtyInBase()).thenReturn(BigDecimal.ZERO);
        when(grLineRepo.findById(7L)).thenReturn(Optional.of(grLine));
        stubDebitNoteParties();

        service.confirm("PRET-UID-PACK");

        verify(grLine).setReturnedQtyInBase(new BigDecimal("25"));
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(any(), any(), any(), any(), any(), any(), payloadCaptor.capture());
        PurchaseReturnedPayload.ReturnLine sent =
                ((PurchaseReturnedPayload) payloadCaptor.getValue()).lines().get(0);
        assertThat(sent.returnedQtyInBase()).isEqualByComparingTo("25");
        assertThat(sent.unitCostAmount()).as("45,000 / 25 bottles").isEqualByComparingTo("1800");
        assertThat(sent.lineValue()).isEqualByComparingTo("45000");
    }

    @Test
    void confirm_aDraftSavedWithTheOldOneToOneConversion_isRefused() {
        stubConfirmableReturn("PRET-UID-STALE", 10L, 20L, 50L);
        PurchaseReturnLine line = stubReturnLine(7L, "GRL-STALE", 1L,
                new BigDecimal("45000"), new BigDecimal("45000"));
        // Pre-fix draft on a crate line: 1 entered, 1 stored as base.
        when(line.getReturnedQty()).thenReturn(new BigDecimal("1"));
        when(line.getReturnedQtyInBase()).thenReturn(new BigDecimal("1"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));

        GoodsReceiptLine grLine = mock(GoodsReceiptLine.class);
        when(grLine.getId()).thenReturn(7L);
        when(grLine.getReceivedQty()).thenReturn(new BigDecimal("8"));
        when(grLine.getQtyInBase()).thenReturn(new BigDecimal("200"));
        when(grLineRepo.findById(7L)).thenReturn(Optional.of(grLine));

        assertThatThrownBy(() -> service.confirm("PRET-UID-STALE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Create a new return");
        verify(grLineRepo, org.mockito.Mockito.never()).save(any());
        verify(outbox, org.mockito.Mockito.never())
                .publish(any(), any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // PUR-03: no return against a voided receipt
    // -------------------------------------------------------------------------

    @Test
    void create_againstAVoidedReceipt_isRefused() {
        stubCreatableReceipt();
        when(createGr.getStatus()).thenReturn(GoodsReceiptStatus.VOID);

        assertThatThrownBy(() -> service.create(createRequest("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("This receipt has been voided");
        verify(returns, org.mockito.Mockito.never()).save(any(PurchaseReturn.class));
    }

    @Test
    void confirm_whenTheReceiptWasVoidedAfterTheDraft_isRefused() {
        stubConfirmableReturn("PRET-UID-V", 10L, 20L, 50L);
        GoodsReceipt voided = mock(GoodsReceipt.class);
        when(voided.getStatus()).thenReturn(GoodsReceiptStatus.VOID);
        when(grRepo.findByCompanyIdAndUid(10L, "GR-OF-PRET-UID-V")).thenReturn(Optional.of(voided));

        assertThatThrownBy(() -> service.confirm("PRET-UID-V"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("This receipt has been voided");
        verify(outbox, org.mockito.Mockito.never())
                .publish(any(), any(), any(), any(), any(), any(), any());
        verify(apDebitNoteService, org.mockito.Mockito.never()).raiseForPurchaseReturn(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // PUR-09: a return posts at the RECEIPT's branch, not the user's current one
    // -------------------------------------------------------------------------

    @Test
    void create_fromAnotherBranch_recordsTheReturnAtTheReceivingBranch() {
        // The user is working in branch 77; the goods were received at branch 20.
        RequestContext.set(new RequestContext.Principal(1L, "user@test.com", false, 10L, 77L, null));
        stubCreatableReceipt();
        stubPackGrLine(new BigDecimal("8"), new BigDecimal("200"),
                new BigDecimal("45000"), new BigDecimal("360000"));
        when(returnLines.sumReturnedQtyInBaseForGrLine(7L)).thenReturn(BigDecimal.ZERO);

        service.create(createRequest("1"));

        ArgumentCaptor<PurchaseReturn> header = forClass(PurchaseReturn.class);
        verify(returns).save(header.capture());
        assertThat(header.getValue().getBranchId()).isEqualTo(20L);
        ArgumentCaptor<PurchaseReturnLine> line = forClass(PurchaseReturnLine.class);
        verify(returnLines).save(line.capture());
        assertThat(line.getValue().getBranchId()).isEqualTo(20L);
    }

    @Test
    void confirm_postsStockAtTheReceivingBranchEvenForAnOldDraftRaisedElsewhere() {
        // Old draft header says branch 77 (the raiser's); the receipt is at branch 20.
        PurchaseReturn ret = stubConfirmableReturn("PRET-UID-BR", 10L, 77L, 50L);
        GoodsReceipt receipt = mock(GoodsReceipt.class);
        when(receipt.getStatus()).thenReturn(GoodsReceiptStatus.RECEIVED);
        when(receipt.getBranchId()).thenReturn(20L);
        when(grRepo.findByCompanyIdAndUid(10L, "GR-OF-PRET-UID-BR")).thenReturn(Optional.of(receipt));
        PurchaseReturnLine line = stubReturnLine(1L, "GRL-BR", 1L,
                new BigDecimal("10.00"), new BigDecimal("100.00"));
        when(returnLines.findByPurchaseReturnIdOrderByLineNo(any())).thenReturn(List.of(line));
        GoodsReceiptLine grLine = stubGrLine(1L, new BigDecimal("20.00"), BigDecimal.ZERO);
        when(grLineRepo.findById(1L)).thenReturn(Optional.of(grLine));
        stubDebitNoteParties();

        service.confirm("PRET-UID-BR");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(20L), payload.capture());
        assertThat(((PurchaseReturnedPayload) payload.getValue()).branchId()).isEqualTo(20L);
        assertThat(ret.getBranchId()).isEqualTo(77L);
    }

    @Test
    void conversionHelpers_roundOnceAndSnapTheLastUlp() {
        GoodsReceiptLine thirds = mock(GoodsReceiptLine.class);
        // 3 packs of a non-integral factor: 10 base units over 3 packs.
        when(thirds.getReceivedQty()).thenReturn(new BigDecimal("3"));
        when(thirds.getQtyInBase()).thenReturn(new BigDecimal("10"));
        assertThat(PurchaseReturnServiceImpl.toBaseQty(BigDecimal.ONE, thirds))
                .isEqualByComparingTo("3.333333");
        // Returning all 3 packs converts to exactly the received base qty.
        assertThat(PurchaseReturnServiceImpl.toBaseQty(new BigDecimal("3"), thirds))
                .isEqualByComparingTo("10");
        // A conversion one ULP over the remainder is a rounding artefact, not an over-return.
        assertThat(PurchaseReturnServiceImpl.snapToRemaining(
                new BigDecimal("6.666667"), new BigDecimal("6.666666")))
                .isEqualByComparingTo("6.666666");
        assertThat(PurchaseReturnServiceImpl.displayQty(new BigDecimal("8.000000"))).isEqualTo("8");
        assertThat(PurchaseReturnServiceImpl.displayQty(new BigDecimal("2.500000"))).isEqualTo("2.5");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GoodsReceipt createGr;

    /** A RECEIVED goods receipt on PO 30, branch 20, supplier 50 in company 10. */
    private void stubCreatableReceipt() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("COMP-UID")).thenReturn(Optional.of(company));

        createGr = mock(GoodsReceipt.class);
        when(createGr.getId()).thenReturn(40L);
        when(createGr.getUid()).thenReturn("GR-UID");
        when(createGr.getPurchaseOrderId()).thenReturn(30L);
        when(createGr.getSupplierId()).thenReturn(50L);
        when(createGr.getBranchId()).thenReturn(20L);
        when(createGr.getCompanyId()).thenReturn(10L);
        when(createGr.getStatus()).thenReturn(GoodsReceiptStatus.RECEIVED);
        when(grRepo.findByCompanyIdAndUid(10L, "GR-UID")).thenReturn(Optional.of(createGr));

        PurchaseOrder po = mock(PurchaseOrder.class);
        when(po.getSupplierCode()).thenReturn("SUP-1");
        when(po.getSupplierName()).thenReturn("Brewer");
        when(poRepo.findById(30L)).thenReturn(Optional.of(po));
        when(numberGen.nextPurchaseReturn(10L)).thenReturn("PRET-0001");

        PurchaseReturn saved = mock(PurchaseReturn.class);
        when(saved.getId()).thenReturn(99L);
        when(saved.getUid()).thenReturn("PRET-UID");
        when(returns.save(any(PurchaseReturn.class))).thenReturn(saved);
        when(returnLines.findMaxLineNo(99L)).thenReturn(0);
    }

    private GoodsReceiptLine stubPackGrLine(BigDecimal receivedQty, BigDecimal qtyInBase,
                                            BigDecimal unitCost, BigDecimal lineCost) {
        GoodsReceiptLine g = mock(GoodsReceiptLine.class);
        when(g.getId()).thenReturn(7L);
        when(g.getUid()).thenReturn("GRL-UID");
        when(g.getGoodsReceipt()).thenReturn(createGr);
        when(g.getProductId()).thenReturn(1L);
        when(g.getProductCode()).thenReturn("SAF");
        when(g.getProductName()).thenReturn("Safari Lager");
        when(g.getUnitId()).thenReturn(3L);
        when(g.getUnitName()).thenReturn("Crate");
        when(g.getReceivedQty()).thenReturn(receivedQty);
        when(g.getQtyInBase()).thenReturn(qtyInBase);
        when(g.getUnitCostAmount()).thenReturn(unitCost);
        when(g.getLineCostAmount()).thenReturn(lineCost);
        when(grLineRepo.findByUid("GRL-UID")).thenReturn(Optional.of(g));
        return g;
    }

    private CreatePurchaseReturnRequest createRequest(String qty) {
        return new CreatePurchaseReturnRequest("COMP-UID", "GR-UID", "Damaged",
                List.of(new CreatePurchaseReturnRequest.ReturnLineRequest(
                        "GRL-UID", new BigDecimal(qty))));
    }

    private void stubDebitNoteParties() {
        Company company = mock(Company.class);
        when(company.getUid()).thenReturn("COMP-UID");
        when(companies.findById(10L)).thenReturn(Optional.of(company));
        Supplier supplier = mock(Supplier.class);
        when(supplier.getUid()).thenReturn("SUPP-UID");
        when(suppliers.findById(50L)).thenReturn(Optional.of(supplier));
        when(apDebitNoteService.raiseForPurchaseReturn(any(), any(), any())).thenReturn(stubDebitNoteDto("DN-UID", "DN-0009"));
    }

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
        when(ret.getGoodsReceiptUid()).thenReturn("GR-OF-" + uid);
        GoodsReceipt receipt = mock(GoodsReceipt.class);
        when(receipt.getStatus()).thenReturn(GoodsReceiptStatus.RECEIVED);
        when(receipt.getBranchId()).thenReturn(branchId);
        when(grRepo.findByCompanyIdAndUid(companyId, "GR-OF-" + uid)).thenReturn(Optional.of(receipt));
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
        when(l.getReturnedQty()).thenReturn(new BigDecimal("10.00"));
        when(l.getUnitCostAmount()).thenReturn(unitCost);
        when(l.getLineValueAmount()).thenReturn(lineValue);
        return l;
    }

    private GoodsReceiptLine stubGrLine(Long id, BigDecimal qtyInBase, BigDecimal alreadyReturned) {
        GoodsReceiptLine g = mock(GoodsReceiptLine.class);
        when(g.getId()).thenReturn(id);
        when(g.getQtyInBase()).thenReturn(qtyInBase);
        when(g.getReceivedQty()).thenReturn(qtyInBase);   // base-unit line: factor 1
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
