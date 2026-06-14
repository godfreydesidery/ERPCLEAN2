package com.erp.modules.purchases.service;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.service.ApDebitNoteService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.PurchaseReturnDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnedPayload;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseReturn;
import com.erp.modules.purchases.domain.entity.PurchaseReturnLine;
import com.erp.modules.purchases.domain.enums.PurchaseReturnStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.PurchaseReturnLineRepository;
import com.erp.modules.purchases.repository.PurchaseReturnRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PurchaseReturnServiceImpl implements PurchaseReturnService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseReturnServiceImpl.class);
    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final String DEFAULT_CURRENCY = "TZS";

    private final PurchaseReturnRepository     returns;
    private final PurchaseReturnLineRepository returnLines;
    private final GoodsReceiptRepository       grRepo;
    private final GoodsReceiptLineRepository   grLineRepo;
    private final PurchaseOrderRepository      poRepo;
    private final CompanyRepository            companies;
    private final SupplierRepository           suppliers;
    private final ApDebitNoteService           apDebitNoteService;
    private final PurchaseNumberGenerator      numberGen;
    private final OutboxPublisher              outbox;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;

    public PurchaseReturnServiceImpl(PurchaseReturnRepository returns,
                                     PurchaseReturnLineRepository returnLines,
                                     GoodsReceiptRepository grRepo,
                                     GoodsReceiptLineRepository grLineRepo,
                                     PurchaseOrderRepository poRepo,
                                     CompanyRepository companies,
                                     SupplierRepository suppliers,
                                     ApDebitNoteService apDebitNoteService,
                                     PurchaseNumberGenerator numberGen,
                                     OutboxPublisher outbox,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.returns           = returns;
        this.returnLines       = returnLines;
        this.grRepo            = grRepo;
        this.grLineRepo        = grLineRepo;
        this.poRepo            = poRepo;
        this.companies         = companies;
        this.suppliers         = suppliers;
        this.apDebitNoteService = apDebitNoteService;
        this.numberGen         = numberGen;
        this.outbox            = outbox;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
    }

    @Override
    public PurchaseReturnDto create(CreatePurchaseReturnRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = branchId(ctx);

        GoodsReceipt gr = grRepo.findByCompanyIdAndUid(companyId, req.goodsReceiptUid())
                .orElseThrow(() -> new NotFoundException("GoodsReceipt: " + req.goodsReceiptUid()));

        // Resolve supplier snapshot from the linked PO
        PurchaseOrder po = poRepo.findById(gr.getPurchaseOrderId())
                .orElseThrow(() -> new NotFoundException("PurchaseOrder id: " + gr.getPurchaseOrderId()));

        String returnNumber = numberGen.nextPurchaseReturn(companyId);
        PurchaseReturn ret = new PurchaseReturn(
                companyId, branchId, returnNumber,
                gr.getId(), gr.getUid(),
                gr.getSupplierId(), po.getSupplierCode(), po.getSupplierName(),
                req.reason(), DEFAULT_CURRENCY, actorId());
        ret = returns.save(ret);

        BigDecimal netTotal = BigDecimal.ZERO;
        for (var l : req.lines()) {
            GoodsReceiptLine grLine = grLineRepo.findByUid(l.goodsReceiptLineUid())
                    .orElseThrow(() -> new NotFoundException("GoodsReceiptLine: " + l.goodsReceiptLineUid()));
            // Validate line belongs to this GR
            if (!grLine.getGoodsReceipt().getId().equals(gr.getId())) {
                throw new IllegalArgumentException(
                        "GR line " + l.goodsReceiptLineUid() + " does not belong to GR "
                                + req.goodsReceiptUid());
            }
            // Validate return qty doesn't exceed returnable balance
            BigDecimal alreadyReturned = returnLines.sumReturnedQtyInBaseForGrLine(grLine.getId());
            BigDecimal maxReturnable = grLine.getQtyInBase().subtract(
                    alreadyReturned != null ? alreadyReturned : BigDecimal.ZERO);
            if (l.returnedQty().compareTo(maxReturnable) > 0) {
                throw new IllegalArgumentException(
                        "Return qty " + l.returnedQty() + " exceeds returnable qty "
                                + maxReturnable + " for line " + l.goodsReceiptLineUid());
            }

            short lineNo = (short) (returnLines.findMaxLineNo(ret.getId()) + 1);
            BigDecimal lineValue = grLine.getUnitCostAmount().multiply(l.returnedQty())
                    .setScale(SCALE, RM);

            PurchaseReturnLine retLine = new PurchaseReturnLine(
                    ret.getId(),
                    grLine.getId(), grLine.getUid(),
                    companyId, branchId, lineNo,
                    grLine.getProductId(), grLine.getProductCode(), grLine.getProductName(),
                    grLine.getUnitId(), grLine.getUnitName(),
                    l.returnedQty(), l.returnedQty(),   // returned_qty_in_base = returned_qty (base UoM)
                    grLine.getUnitCostAmount(), lineValue,
                    DEFAULT_CURRENCY, actorId());
            returnLines.save(retLine);
            netTotal = netTotal.add(lineValue);
        }

        ret.setNetAmount(netTotal.setScale(SCALE, RM));
        ret.setGrossAmount(netTotal.setScale(SCALE, RM));
        ret.setUpdatedAt(Instant.now());
        ret.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PURCHASE_RETURN_CREATE, "purchase_returns",
                ret.getId(), ret.getUid())
                .detail(Map.of("returnNumber", returnNumber, "grUid", req.goodsReceiptUid())));
        return toDto(ret);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseReturnDto getByUid(String uid) {
        PurchaseReturn ret = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), ret.getCompanyId());
        return toDto(ret);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseReturnDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return returns.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public PurchaseReturnDto confirm(String uid) {
        PurchaseReturn ret = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), ret.getCompanyId());
        if (ret.getStatus() != PurchaseReturnStatus.DRAFT) {
            throw new IllegalStateException(
                    "Can only confirm a DRAFT purchase return; current: " + ret.getStatus());
        }

        List<PurchaseReturnLine> lines = returnLines.findByPurchaseReturnIdOrderByLineNo(ret.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException("Cannot confirm a purchase return with no lines.");
        }

        // Build outbox payload and update GR line returned_qty_in_base
        List<PurchaseReturnedPayload.ReturnLine> payloadLines = new ArrayList<>();
        BigDecimal totalReturnValue = BigDecimal.ZERO;

        for (PurchaseReturnLine line : lines) {
            totalReturnValue = totalReturnValue.add(line.getLineValueAmount());

            // RE-VALIDATE before update (BR-PROC-10 guard against concurrent confirms).
            // Also performs the increment under the same lock — prevents over-return race.
            grLineRepo.findById(line.getGoodsReceiptLineId()).ifPresent(grLine -> {
                BigDecimal current = grLine.getReturnedQtyInBase() != null
                        ? grLine.getReturnedQtyInBase() : BigDecimal.ZERO;
                BigDecimal newTotal = current.add(line.getReturnedQtyInBase());
                if (newTotal.compareTo(grLine.getQtyInBase()) > 0) {
                    throw new IllegalArgumentException(
                            "Return qty " + line.getReturnedQtyInBase() + " for GR line "
                            + line.getGoodsReceiptLineUid() + " would exceed receipted qty "
                            + grLine.getQtyInBase() + " (already returned: " + current
                            + ", total would be: " + newTotal + ") — BR-PROC-10");
                }
                grLine.setReturnedQtyInBase(newTotal);
                grLineRepo.save(grLine);
            });

            payloadLines.add(new PurchaseReturnedPayload.ReturnLine(
                    line.getGoodsReceiptLineId(), line.getGoodsReceiptLineUid(),
                    line.getProductId(),
                    line.getReturnedQtyInBase(), line.getUnitCostAmount(),
                    line.getLineValueAmount()));
        }

        ret.setStatus(PurchaseReturnStatus.CONFIRMED);
        ret.setConfirmedAt(Instant.now());
        ret.setConfirmedBy(actorId());
        ret.setUpdatedAt(Instant.now());
        ret.setUpdatedBy(actorId());

        // Publish outbox: stock handler reverses qty + posts DR GRNI / CR INVENTORY (ADR-0027 D-7).
        // billed=false is the conservative default: GRNI path is always correct for not-yet-billed
        // receipts (the common case).  When the receipt WAS billed before the return, the GRNI
        // re-open is a known accepted imprecision (ADR-0027 OQ-RETURN-GL); the AP debit note raised
        // below reduces the payable regardless.
        PurchaseReturnedPayload payload = new PurchaseReturnedPayload(
                ret.getUid(), ret.getCompanyId(), ret.getBranchId(),
                totalReturnValue, DEFAULT_CURRENCY, false, payloadLines,
                ret.getReturnNumber());
        outbox.publish(DomainEventType.PURCHASE_RETURNED,
                DomainEventType.AGG_PURCHASE_RETURN,
                ret.getId(), ret.getUid(),
                ret.getCompanyId(), ret.getBranchId(), payload);

        // Raise AP debit note synchronously in this TX (ADR-0027 D-7 step 4).
        // DR AP / CR Purchases to reduce the supplier payable for the returned goods.
        if (totalReturnValue.signum() > 0) {
            String companyUid = companies.findById(ret.getCompanyId())
                    .orElseThrow(() -> new NotFoundException("Company id: " + ret.getCompanyId()))
                    .getUid();
            String supplierUid = suppliers.findById(ret.getSupplierId())
                    .orElseThrow(() -> new NotFoundException("Supplier id: " + ret.getSupplierId()))
                    .getUid();

            RaiseDebitNoteRequest debitNoteReq = new RaiseDebitNoteRequest(
                    companyUid,
                    supplierUid,
                    null,                                // no specific bill — general supplier credit
                    LocalDate.now(),
                    totalReturnValue,
                    BigDecimal.ZERO,                     // no VAT on the goods cost reversal
                    "Purchase return " + ret.getReturnNumber() + " [" + ret.getUid() + "]: " + ret.getReason(),
                    "PURCHASE_RETURN");                  // origin matches CHECK constraint in ap_debit_notes

            ApDebitNoteDto debitNote = apDebitNoteService.raise(debitNoteReq);
            ret.setDebitNoteUid(debitNote.uid());
            log.info("Purchase return {} confirmed; AP debit note {} raised (uid={}).",
                    ret.getReturnNumber(), debitNote.debitNoteNumber(), debitNote.uid());
        }

        audit.record(AuditEvent.of(AuditActions.PURCHASE_RETURN_CONFIRM, "purchase_returns",
                ret.getId(), ret.getUid())
                .detail(Map.of("returnNumber", ret.getReturnNumber(),
                        "totalReturnValue", totalReturnValue.toPlainString())));
        return toDto(ret);
    }

    // -------------------------------------------------------------------------

    private PurchaseReturn require(String uid) {
        return Lookups.orNotFound(returns.findByUid(uid), "PurchaseReturn", uid);
    }

    private PurchaseReturnDto toDto(PurchaseReturn r) {
        List<PurchaseReturnLineDto> lineDtos = returnLines
                .findByPurchaseReturnIdOrderByLineNo(r.getId())
                .stream().map(PurchaseReturnLineDto::from).toList();
        return PurchaseReturnDto.from(r, lineDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchId(RequestContext.Principal ctx) {
        Long id = ctx != null ? ctx.branchId() : null;
        if (id == null) throw new IllegalStateException("No active branch in context.");
        return id;
    }
}
