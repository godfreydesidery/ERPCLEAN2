package com.erp.modules.purchases.service;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct goods receipt — stock received with no prior LPO (K3, Kilimanjaro 2026-08-08).
 *
 * <p><b>Why this exists.</b> The client buys from walk-in suppliers and for cash. Before this, the
 * only way to get that stock onto the books was a stock adjustment or a bulk import — which raises
 * on-hand but records no supplier, no document and no purchase cost: it values the movement at the
 * CURRENT moving average (zero for a brand-new product) and posts a variance hit instead of
 * DR INVENTORY / CR GRNI. Inventory ended up under-valued and cost overstated.
 *
 * <p><b>How it works.</b> Rather than making {@code goods_receipts.purchase_order_id} nullable and
 * teaching every downstream consumer about receipts with no order, this service <b>auto-raises the
 * purchase order</b> and receives against it. It is deliberately a thin composition over the two
 * shipped services — {@link PurchaseOrderService#createWithOrigin}/{@link PurchaseOrderService#placeOrder}
 * and {@link GoodsReceiptService#createAndReceive} — so it inherits, rather than re-implements: supplier
 * and product company-scoping (F15), unit→base conversion and the pack-factor rules, the zero-cost
 * note rule, PO-####/GRN-#### numbering, the outstanding tracker, the {@code STOCK.RECEIVED} outbox
 * event, and the audit trail. There is no second code path that can drift from the normal one.
 *
 * <p><b>Atomicity.</b> Everything runs in ONE transaction (the composed services join it with the
 * default REQUIRED propagation), so a failure at any step leaves neither a phantom purchase order
 * nor a partial receipt.
 *
 * <p><b>Spend approval: DETECTIVE, not preventive</b> (owner decision, 2026-08-08). The backing
 * order is exempt from the {@link PoApprovalGate} threshold check at placement, because the order
 * does not exist until this very method creates it — there was no moment at which anyone could have
 * approved it, so the gate made every direct receipt in an approval-enabled company impossible
 * (HTTP 409; zero rows had ever been created). The goods are already on the loading dock: refusing
 * to record them does not un-buy them, it only makes stock wrong — the worse failure, and precisely
 * what drove this client onto the stock-adjustment workaround that mis-values inventory. So the
 * receipt completes and step 4 immediately raises a <b>ratification</b> request through the normal
 * approvals engine, landing in the same inbox managers already use. The spend is still reviewed;
 * it is reviewed after the event, which is the only point at which review is possible.
 *
 * <p><b>Provenance.</b> The backing order is stamped
 * {@link com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin#DIRECT_RECEIPT} (V96), so it is
 * distinguishable from a buyer's order for ever after and is filtered out of the default
 * purchase-order list. The audit row written at the end of {@link #receiveDirect} records the same
 * fact against the receipt.
 */
@Service
@Transactional
public class DirectGoodsReceiptServiceImpl implements DirectGoodsReceiptService {

    private static final Logger log = LoggerFactory.getLogger(DirectGoodsReceiptServiceImpl.class);

    /**
     * Audit action for the direct path. Declared locally rather than in {@code AuditActions} for the
     * same reason {@code DocumentRenderServiceImpl} declares {@code DOCUMENT.RENDER} locally — the
     * {@code audit_log.action} column is unconstrained, so no migration or shared-file edit is needed.
     */
    private static final String PURCHASE_GR_RECEIVE_DIRECT = "PURCHASE.GOODS_RECEIPT.RECEIVE_DIRECT";

    /** Fallback when the company row carries no base currency (matches PurchaseOrderServiceImpl). */
    private static final String FALLBACK_CURRENCY = "TZS";

    /** {@code purchase_orders.notes} / {@code goods_receipts.notes} are both VARCHAR(500). */
    private static final int NOTES_MAX_LENGTH = 500;

    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService  goodsReceipts;
    private final CompanyRepository    companies;
    private final AuditService         audit;

    public DirectGoodsReceiptServiceImpl(PurchaseOrderService purchaseOrders,
                                          GoodsReceiptService goodsReceipts,
                                          CompanyRepository companies,
                                          AuditService audit) {
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts  = goodsReceipts;
        this.companies      = companies;
        this.audit          = audit;
    }

    @Override
    public GoodsReceiptDto receiveDirect(DirectGoodsReceiptRequest req) {
        validate(req);

        // 1. Draft the backing PO. Company scope, supplier resolution, product/unit resolution, the
        //    pack-factor conversion and the zero-cost-needs-a-note rule are all enforced in here.
        List<AddPurchaseOrderLineRequest> poLines = req.lines().stream()
                .map(l -> new AddPurchaseOrderLineRequest(
                        l.productUid(), l.unitUid(), l.receivedQty(), l.unitCostAmount(), l.note()))
                .toList();

        // The PO is stamped DIRECT_RECEIPT at construction (V96), which is what keeps these
        // synthesised orders out of the buyer's purchase-order list — see PurchaseOrderService#list.
        PurchaseOrderDto draft = purchaseOrders.createWithOrigin(new CreatePurchaseOrderRequest(
                        req.companyUid(),
                        req.supplierUid(),
                        resolveCurrency(req),
                        backingOrderNotes(req),
                        null,            // expectedDate — the goods are already here
                        null,            // paymentTermsUid — supplier default applies
                        poLines),
                PurchaseOrderOrigin.DIRECT_RECEIPT);

        // 2. Place it: DRAFT → ORDERED, assigns PO-####, and runs the spend-approval gate.
        PurchaseOrderDto placed = placeBackingOrder(draft);

        // 3. Receive every line in full. Quantities are read back from the PLACED PO lines so the
        //    receipt closes the order exactly — the same numbers the outstanding tracker will check.
        GoodsReceiptDto receipt = goodsReceipts.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), truncateNotes(req.notes()), buildReceiptLines(req, placed)));

        // 4. Post-hoc RATIFICATION (owner decision 2026-08-08). The goods are in stock; the spend
        //    now goes to the standard approvals inbox for review. Same TX as the receipt, so the
        //    movement and the review request commit together — stock can never move without the
        //    ratification that covers it also existing. Deliberately NOT wrapped in a catch: this
        //    joins the caller's transaction, so swallowing a failure here would only trade a clear
        //    error for an UnexpectedRollbackException at commit.
        purchaseOrders.requestDirectReceiptRatification(placed.uid());

        // 5. Provenance. createAndReceive already audited PURCHASE.GOODS_RECEIPT.RECEIVE; this row
        //    records that the receipt came in through the direct path and which PO was synthesised
        //    for it, so the phantom order is explainable long after the fact.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("origin", PurchaseOrderOrigin.DIRECT_RECEIPT.name());
        detail.put("receiptNumber", nullSafe(receipt.receiptNumber()));
        detail.put("purchaseOrderUid", nullSafe(placed.uid()));
        detail.put("purchaseOrderNumber", nullSafe(placed.orderNumber()));
        detail.put("supplierUid", nullSafe(req.supplierUid()));
        detail.put("lineCount", String.valueOf(req.lines().size()));
        audit.record(AuditEvent.of(PURCHASE_GR_RECEIVE_DIRECT, "goods_receipts",
                receipt.id(), receipt.uid()).detail(detail));

        return receipt;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validate(DirectGoodsReceiptRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("No receipt details were supplied.");
        }
        if (isBlank(req.companyUid())) {
            throw new IllegalArgumentException("Select the company this delivery belongs to.");
        }
        if (isBlank(req.supplierUid())) {
            throw new IllegalArgumentException("Select the supplier who delivered these goods.");
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalArgumentException("Add at least one item to receive.");
        }
        for (DirectGoodsReceiptLineRequest line : req.lines()) {
            if (line == null || isBlank(line.productUid()) || isBlank(line.unitUid())) {
                throw new IllegalArgumentException(
                        "Every line needs a product and the unit it was delivered in.");
            }
            // Quantity and cost are validated by the PO line path (positive qty; cost >= 0; a zero
            // cost needs a note) — deliberately not duplicated here so the two paths cannot diverge.
        }
    }

    /**
     * Places the backing order, restating any refusal in the storekeeper's language.
     *
     * <p>The approval refusal that used to be restated here is gone by construction — a
     * DIRECT_RECEIPT order is exempt from pre-approval and ratified afterwards instead (see the
     * class javadoc and {@link PoApprovalGate#isExemptFromPreApproval}). What remains are
     * placement-integrity failures, whose stock messages name a purchase order the direct-receipt
     * caller never saw and cannot find; the cause is chained for the log and never reaches the
     * client (the global handler surfaces {@code getMessage()} only).
     */
    private PurchaseOrderDto placeBackingOrder(PurchaseOrderDto draft) {
        try {
            return purchaseOrders.placeOrder(draft.uid());
        } catch (IllegalStateException ex) {
            log.warn("Direct goods receipt blocked while placing the backing purchase order: {}",
                    ex.getMessage());
            throw new IllegalStateException(
                    "This delivery could not be recorded. Please check the items and quantities, "
                            + "then try again.", ex);
        }
    }

    /**
     * Pairs each requested line with the PO line it produced. The order is safe because this method
     * created the PO itself, one line per request line in request order, and both
     * {@code PurchaseOrderDto.lines()} and the line-number allocation are ordered by {@code line_no}.
     * The size check makes that assumption explicit rather than implicit.
     */
    private List<GoodsReceiptLineRequest> buildReceiptLines(DirectGoodsReceiptRequest req,
                                                            PurchaseOrderDto placed) {
        List<PurchaseOrderLineDto> poLines = placed.lines();
        if (poLines == null || poLines.size() != req.lines().size()) {
            log.error("Direct goods receipt: auto-raised PO uid={} has {} lines for {} requested lines",
                    placed.uid(), poLines != null ? poLines.size() : 0, req.lines().size());
            throw new IllegalStateException(
                    "The delivery could not be recorded. Please try again.");
        }

        List<GoodsReceiptLineRequest> grLines = new ArrayList<>(poLines.size());
        for (int i = 0; i < poLines.size(); i++) {
            PurchaseOrderLineDto poLine = poLines.get(i);
            DirectGoodsReceiptLineRequest src = req.lines().get(i);
            grLines.add(new GoodsReceiptLineRequest(
                    poLine.uid(),
                    poLine.orderedQty(),          // receive the order in full — nothing outstanding
                    src.lotNumber(),
                    src.manufactureDate(),
                    src.expiryDate(),
                    src.serialNumbers()));
        }
        return grLines;
    }

    /** Requested currency if present, else the company's base currency (mirrors the PO convert path). */
    private String resolveCurrency(DirectGoodsReceiptRequest req) {
        if (!isBlank(req.currency())) {
            return req.currency();
        }
        return companies.findByUid(req.companyUid())
                .map(Company::getBaseCurrency)
                .filter(c -> !isBlank(c))
                .orElse(FALLBACK_CURRENCY);
    }

    /**
     * The note stamped on the synthesised PO. It is the only thing on the order itself that explains
     * why it exists to a buyer who opens it, so it stays even once the {@code origin} column lands.
     */
    private String backingOrderNotes(DirectGoodsReceiptRequest req) {
        String base = "Raised automatically for a direct goods receipt (no prior LPO).";
        String combined = isBlank(req.notes()) ? base : base + " " + req.notes().trim();
        return truncateNotes(combined);
    }

    /**
     * The notes columns are VARCHAR(500); truncate rather than let an over-long note fail the whole
     * receipt with an opaque data-truncation error.
     */
    private static String truncateNotes(String notes) {
        if (notes == null || notes.length() <= NOTES_MAX_LENGTH) {
            return notes;
        }
        return notes.substring(0, NOTES_MAX_LENGTH);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
