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
    /** Scale of every quantity-in-base column ({@code numeric(19,6)}). */
    private static final int BASE_QTY_SCALE = 6;
    /** One unit in the last place of a base quantity (see {@link #snapToRemaining}). */
    private static final BigDecimal BASE_QTY_ULP = new BigDecimal("0.000001");

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
                .orElseThrow(() -> new NotFoundException("Company not found."));
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = branchId(ctx);

        GoodsReceipt gr = grRepo.findByCompanyIdAndUid(companyId, req.goodsReceiptUid())
                .orElseThrow(() -> new NotFoundException("Goods receipt not found."));

        // Resolve supplier snapshot from the linked PO
        PurchaseOrder po = poRepo.findById(gr.getPurchaseOrderId())
                .orElseThrow(() -> new NotFoundException("Purchase order not found."));

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
                    .orElseThrow(() -> new NotFoundException("Goods receipt line not found."));
            // Validate line belongs to this GR
            if (!grLine.getGoodsReceipt().getId().equals(gr.getId())) {
                throw new IllegalArgumentException(
                        "A goods receipt line does not belong to the specified goods receipt.");
            }

            // PUR-02 / LBO-01: returnedQty is entered in the RECEIPT LINE's unit (a crate when the
            // line was received in crates), exactly as receivedQty is on the receipt itself. The
            // stock ledger, the over-return guard and the moving average all work in BASE units,
            // so convert once, with the line's own factor, before anything else touches it.
            BigDecimal alreadyReturned = returnLines.sumReturnedQtyInBaseForGrLine(grLine.getId());
            BigDecimal remainingBase = grLine.getQtyInBase().subtract(
                    alreadyReturned != null ? alreadyReturned : BigDecimal.ZERO);
            BigDecimal qtyInBase = snapToRemaining(toBaseQty(l.returnedQty(), grLine), remainingBase);
            if (qtyInBase.signum() <= 0) {
                throw new IllegalArgumentException(
                        "The return quantity for " + grLine.getProductName()
                                + " is too small to record. Enter a larger quantity and try again.");
            }
            // LBO-02: compare base with base. The old check compared crates against bottles.
            if (qtyInBase.compareTo(remainingBase) > 0) {
                log.warn("Over-return rejected on GR line id={}: returnedQty={} -> qtyInBase={}, "
                                + "remainingBase={}", grLine.getId(), l.returnedQty(), qtyInBase,
                        remainingBase);
                throw new IllegalArgumentException(
                        "You can return at most " + displayQty(fromBaseQty(remainingBase.max(BigDecimal.ZERO), grLine))
                                + " " + grLine.getUnitName() + " of " + grLine.getProductName()
                                + " on this receipt.");
            }

            short lineNo = (short) (returnLines.findMaxLineNo(ret.getId()) + 1);
            // Value at the receipt's own cost for exactly the base quantity leaving stock — the same
            // per-base cost the receipt put INTO the moving average (GoodsReceiptServiceImpl
            // .baseUnitCost), so a return backs out what the receipt added, no more and no less.
            BigDecimal lineValue = returnValue(qtyInBase, l.returnedQty(), grLine);

            PurchaseReturnLine retLine = new PurchaseReturnLine(
                    ret.getId(),
                    grLine.getId(), grLine.getUid(),
                    companyId, branchId, lineNo,
                    grLine.getProductId(), grLine.getProductCode(), grLine.getProductName(),
                    grLine.getUnitId(), grLine.getUnitName(),
                    l.returnedQty(), qtyInBase,   // returned_qty in the line's unit; in base for stock
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
            throw new IllegalStateException("This purchase return has already been confirmed.");
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
            GoodsReceiptLine grLine = grLineRepo.findById(line.getGoodsReceiptLineId())
                    .orElseThrow(() -> new NotFoundException("Goods receipt line not found."));

            // PUR-02: a draft saved before returns were converted from the line's unit carries
            // returned_qty_in_base = returned_qty even on a crate line. Confirming it would post a
            // quantity nobody can vouch for (did the clerk mean crates or bottles?), so refuse it
            // rather than guess. A base-unit line converts to itself and is unaffected.
            BigDecimal expectedBase = toBaseQty(line.getReturnedQty(), grLine);
            if (line.getReturnedQtyInBase() == null
                    || expectedBase.subtract(line.getReturnedQtyInBase()).abs().compareTo(BASE_QTY_ULP) > 0) {
                log.warn("Purchase return {} line grLineId={} holds a stale base quantity: returnedQty={}, "
                                + "stored qtyInBase={}, expected={}", ret.getReturnNumber(),
                        grLine.getId(), line.getReturnedQty(), line.getReturnedQtyInBase(), expectedBase);
                throw new IllegalStateException(
                        "This draft return was recorded before pack quantities were converted, so "
                                + "its quantities can't be trusted. Create a new return for these goods.");
            }

            BigDecimal current = grLine.getReturnedQtyInBase() != null
                    ? grLine.getReturnedQtyInBase() : BigDecimal.ZERO;
            BigDecimal newTotal = current.add(line.getReturnedQtyInBase());
            if (newTotal.compareTo(grLine.getQtyInBase()) > 0) {
                // BR-PROC-10: total returned quantity cannot exceed the original receipted quantity.
                // Internal figures go to the log; the user gets the remainder in the line's unit.
                log.warn("Over-return rejected at confirm on GR line id={}: alreadyReturnedBase={}, "
                                + "receivedBase={}, requestedBase={}", grLine.getId(), current,
                        grLine.getQtyInBase(), line.getReturnedQtyInBase());
                BigDecimal remaining = grLine.getQtyInBase().subtract(current).max(BigDecimal.ZERO);
                throw new IllegalArgumentException(
                        "The return quantity cannot exceed the original receipted quantity for this line. "
                                + "You can still return at most "
                                + displayQty(fromBaseQty(remaining, grLine)) + " "
                                + grLine.getUnitName() + " of " + grLine.getProductName() + ".");
            }
            grLine.setReturnedQtyInBase(newTotal);
            grLineRepo.save(grLine);

            // The stock handler pairs unitCostAmount with the BASE quantity on the movement row, so
            // send the cost of one base unit, not the receipt line's per-pack cost.
            payloadLines.add(new PurchaseReturnedPayload.ReturnLine(
                    line.getGoodsReceiptLineId(), line.getGoodsReceiptLineUid(),
                    line.getProductId(),
                    line.getReturnedQtyInBase(),
                    perBaseUnitCost(line.getLineValueAmount(), line.getReturnedQtyInBase()),
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
                    .orElseThrow(() -> new NotFoundException("Company not found."))
                    .getUid();
            String supplierUid = suppliers.findById(ret.getSupplierId())
                    .orElseThrow(() -> new NotFoundException("Supplier not found."))
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
    // Unit conversion (PUR-02 / LBO-01 / LBO-02)
    // -------------------------------------------------------------------------

    /**
     * A quantity in the receipt line's unit, in base units — {@code qty × qtyInBase ÷ receivedQty}.
     * One multiplication and one rounding step, the same shape {@code GoodsReceiptServiceImpl}
     * uses to convert the receipt itself, so a full return lands exactly on the received base qty.
     * A line without a usable received qty (never written by the service) is treated as base.
     */
    static BigDecimal toBaseQty(BigDecimal qty, GoodsReceiptLine grLine) {
        if (qty == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal received = grLine.getReceivedQty();
        BigDecimal base     = grLine.getQtyInBase();
        if (received == null || received.signum() <= 0 || base == null) {
            return qty.setScale(BASE_QTY_SCALE, RM);
        }
        return qty.multiply(base).divide(received, BASE_QTY_SCALE, RM);
    }

    /** The inverse of {@link #toBaseQty}: base units back into the receipt line's unit. */
    static BigDecimal fromBaseQty(BigDecimal baseQty, GoodsReceiptLine grLine) {
        BigDecimal received = grLine.getReceivedQty();
        BigDecimal base     = grLine.getQtyInBase();
        if (received == null || base == null || base.signum() <= 0) {
            return baseQty;
        }
        return baseQty.multiply(received).divide(base, BASE_QTY_SCALE, RM);
    }

    /**
     * Snap a conversion that lands at most one ULP above the remainder onto it — returning the
     * whole of a line received in a pack whose factor does not divide evenly must not read as an
     * over-return (K4, the same artefact the receipt path absorbs).
     */
    static BigDecimal snapToRemaining(BigDecimal qtyInBase, BigDecimal remainingBase) {
        if (remainingBase.signum() > 0
                && qtyInBase.compareTo(remainingBase) > 0
                && qtyInBase.subtract(remainingBase).compareTo(BASE_QTY_ULP) <= 0) {
            return remainingBase;
        }
        return qtyInBase;
    }

    /**
     * Value of {@code qtyInBase} at the receipt line's own cost: {@code lineCost × qtyInBase ÷
     * receivedBase}. Falls back to the per-line-unit cost × entered qty only for a line with no
     * stored total (never written by the service, kept defensive).
     */
    static BigDecimal returnValue(BigDecimal qtyInBase, BigDecimal returnedQty, GoodsReceiptLine grLine) {
        BigDecimal lineCost = grLine.getLineCostAmount();
        BigDecimal base     = grLine.getQtyInBase();
        if (lineCost != null && base != null && base.signum() > 0) {
            return lineCost.multiply(qtyInBase).divide(base, SCALE, RM);
        }
        BigDecimal unitCost = grLine.getUnitCostAmount() != null
                ? grLine.getUnitCostAmount() : BigDecimal.ZERO;
        return unitCost.multiply(returnedQty).setScale(SCALE, RM);
    }

    /** Cost of one base unit for the stock movement row; zero for a defensive zero quantity. */
    static BigDecimal perBaseUnitCost(BigDecimal value, BigDecimal qtyInBase) {
        if (value == null || qtyInBase == null || qtyInBase.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(qtyInBase, SCALE, RM);
    }

    /** A quantity for a user-facing message: no trailing zeros, no exponent. */
    static String displayQty(BigDecimal qty) {
        BigDecimal stripped = qty.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
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
