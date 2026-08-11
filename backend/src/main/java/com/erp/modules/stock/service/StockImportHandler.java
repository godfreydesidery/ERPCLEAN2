package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.bulk.BulkImportHandler;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportParsers;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk STOCK update — one sheet that sets each product's on-hand level at the caller's <b>active
 * branch</b> to a counted quantity, mirroring the price upload (you type the new value, not a
 * delta). Stock is an append-only movement ledger, so "set the level" is expressed as an
 * ADJUSTMENT: the handler reads the live current on-hand, computes {@code delta = new − current},
 * and posts it through the existing {@link StockService#adjust} call — so every row gets the same
 * validation, negative-stock guard, moving-average valuation, GL posting, audit and outbox as a
 * hand-entered adjustment on the single screen. Products are addressed by their human code.
 *
 * <p><b>Branch scope.</b> Like the single Adjust screen, an import always targets the caller's
 * active branch (the {@code X-Branch-Uid} switch) — never a column. Switch branch first to update a
 * different one. A product stocked across <b>several locations</b> in the branch cannot be set to a
 * single branch total this way (the underlying adjust posts to one location) — such a row is
 * reported as a {@code SKIP} pointing to the per-location screens, so the rest of the sheet still
 * imports.
 *
 * <p><b>Permission.</b> Gated by {@code STOCK.IMPORT} — a distinct, higher-privilege capability than
 * the single-record {@code STOCK.ADJUST} (which this handler does not additionally require): a role
 * can be granted mass import without manual adjust, or vice-versa. The optional <b>Unit Cost</b>
 * column additionally requires {@code INVENTORY.OPENING.SET}, because it posts a GL entry.
 *
 * <p><b>Unit Cost (opening valuation).</b> An ADJUSTMENT can only carry an existing moving average
 * forward — it can never establish one. So stock first brought in through this sheet (rather than a
 * purchase order) landed with a quantity but no cost: blank on the stock report, excluded from the
 * valuation total, and posting NO cost-of-sale when sold, which overstates gross profit. Filling in
 * Unit Cost sets the one-time opening valuation through {@link InventoryValuationService#setOpeningValue}
 * (ADR-0020 D-5b) — the same ratified path as the Opening Valuation screen, GL leg included. It
 * never overwrites an existing valuation: an already-valued product is reported and left alone, so
 * a later cost change still goes through a purchase or a revaluation.
 *
 * <p><b>Download → edit → re-upload.</b> {@link #exportRows} emits one row per active, stockable
 * product with its current on-hand shown in a read-only reference column; the editable "New On-Hand
 * Qty" is left blank so a re-upload only touches the rows you fill in. A <b>blank New On-Hand Qty is
 * left unchanged</b>, and a value equal to the current level is a no-op — so you can export every
 * product, count only some, and upload the whole sheet.
 *
 * <p>For an arbitrary +/- correction with a specific reason, this same sheet works: set the New
 * On-Hand Qty to the level you want and pick the Reason (default {@link AdjustmentReason#COUNT_CORRECTION}).
 */
@Component
@Transactional
public class StockImportHandler implements BulkImportHandler {

    private static final int EXPORT_MAX = 2000;

    private static final String COL_PRODUCT = "Product Code";
    private static final String COL_NAME    = "Product Name";
    private static final String COL_CURRENT = "Current On-Hand";
    private static final String COL_NEW     = "New On-Hand Qty";
    private static final String COL_COST    = "Unit Cost";
    private static final String COL_REASON  = "Reason";
    private static final String COL_NOTE    = "Note";

    /** Higher-privilege capability: establishing a cost posts to the GL (ADR-0020 D-5b). */
    private static final String PERM_OPENING_SET = "INVENTORY.OPENING.SET";

    /**
     * Posting date is the operator's business day, not the server's. On a UTC host an evening
     * upload in Dar would otherwise post to the following day and land in the wrong period.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final StockService stockService;
    private final ProductService productService;
    private final StockOnHandRepository onHands;
    private final InventoryValuationService valuation;
    private final PermissionResolver permissionResolver;

    public StockImportHandler(StockService stockService,
                              ProductService productService,
                              StockOnHandRepository onHands,
                              InventoryValuationService valuation,
                              PermissionResolver permissionResolver) {
        this.stockService = stockService;
        this.productService = productService;
        this.onHands = onHands;
        this.valuation = valuation;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public String key() {
        return "stock";
    }

    @Override
    public String displayName() {
        return "Stock on-hand levels";
    }

    @Override
    public String permissionCode() {
        return "STOCK.IMPORT";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<String> reasons = Arrays.stream(AdjustmentReason.values()).map(Enum::name).toList();
        return List.of(
                ColumnSpec.of(COL_PRODUCT, true, "Existing product code."),
                ColumnSpec.reference(COL_NAME, "The product's name — to identify the row."),
                ColumnSpec.numberReference(COL_CURRENT,
                        "The current on-hand level at your active branch, shown for reference. Not "
                      + "imported — the difference is always computed from the live level."),
                ColumnSpec.number(COL_NEW, false,
                        "The quantity you want ON HAND. The system posts the difference from the "
                      + "current level as an adjustment. Blank = LEAVE UNCHANGED; a value equal to "
                      + "the current level is a no-op."),
                ColumnSpec.number(COL_COST, false,
                        "Buying price per unit, for a product that has never been valued (stock "
                      + "brought in outside a purchase order). Sets the opening valuation ONCE, so "
                      + "the item shows a cost and value on the stock and valuation reports and "
                      + "posts cost-of-sale when sold. Blank = leave the cost as it is. Ignored "
                      + "with a note if the product is already valued — a later cost change goes "
                      + "through a purchase or a revaluation, never here."),
                ColumnSpec.choice(COL_REASON, false,
                        "Adjustment reason for the difference. Blank = COUNT_CORRECTION.", reasons),
                ColumnSpec.of(COL_NOTE, false, "Optional note recorded on the adjustment."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        BigDecimal target = ImportParsers.parseDecimal(row, COL_NEW);
        BigDecimal unitCost = ImportParsers.parseDecimal(row, COL_COST);
        if (unitCost != null && unitCost.signum() < 0) {
            throw new IllegalArgumentException("'" + COL_COST + "' cannot be negative.");
        }
        if (target == null && unitCost == null) {
            return RowOutcome.skip(row.rowNumber(), row.get(COL_PRODUCT),
                    "No new on-hand quantity entered — left unchanged.");
        }
        if (target != null && target.signum() < 0) {
            throw new IllegalArgumentException("'" + COL_NEW + "' cannot be negative.");
        }

        String productCode = ImportParsers.requireText(row, COL_PRODUCT).trim();
        ProductDto product = resolveProduct(companyId, productCode);

        // On-hand rows for this product at the active branch, across every location.
        List<StockOnHand> onHandRows = onHands.findAllByCompanyIdAndBranchIdAndProductId(
                companyId, activeBranchId(), product.id());
        long locations = onHandRows.stream().map(StockOnHand::getLocationId).distinct().count();
        if (locations > 1) {
            // adjust() posts to the branch's default location and reads a single on-hand row, so it
            // cannot set a product spread across several locations to one branch total. Skip it
            // (never block the rest of the sheet) with a pointer to the per-location screens.
            return RowOutcome.skip(row.rowNumber(), product.code(),
                    "Stocked at " + locations + " locations in this branch — set its level from the "
                  + "per-location stock screens (a bulk set adjusts a single location only).");
        }

        BigDecimal current = onHandRows.stream()
                .map(soh -> soh.getQuantity() != null ? soh.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // A blank quantity column is "leave the level alone", which is a zero delta — so a row that
        // carries only a cost still reaches the valuation step below.
        BigDecimal delta = target == null ? BigDecimal.ZERO : target.subtract(current);

        if (delta.signum() == 0 && unitCost == null) {
            return RowOutcome.skip(row.rowNumber(), product.code(),
                    "Already at " + fmt(current) + " — no change.");
        }
        if (delta.signum() != 0) {
            applyQuantity(product, delta, row);
        }
        // Cost AFTER quantity, deliberately: opening value is qty × cost, so valuing first would
        // value the level the row is replacing. An adjustment can only carry an average forward, it
        // can never create one — which is why stock brought in this way (no purchase order) used to
        // sit at zero value, drop out of the valuation report, and post NO cost-of-sale when sold.
        CostOutcome cost = unitCost == null
                ? CostOutcome.NONE
                : applyOpeningCost(companyId, product, unitCost);

        String detail = describeOutcome(product, target, delta, cost.note());
        // Report what actually happened. A row whose cost was refused and whose level did not move
        // changed nothing, and counting it as "updated" would inflate the operator's summary.
        return delta.signum() == 0 && !cost.applied()
                ? RowOutcome.skip(row.rowNumber(), product.code(), cost.note())
                : RowOutcome.update(row.rowNumber(), detail);
    }

    /** Whether the opening valuation was actually written, plus the note to report either way. */
    private record CostOutcome(boolean applied, String note) {
        static final CostOutcome NONE = new CostOutcome(false, null);
    }

    /** Post the level change through the same service the single Adjust screen uses. */
    private void applyQuantity(ProductDto product, BigDecimal delta, ImportRow row) {
        AdjustmentReason reason = ImportParsers.parseEnum(
                AdjustmentReason.class, row, COL_REASON, AdjustmentReason.COUNT_CORRECTION);
        String note = ImportParsers.text(row, COL_NOTE);
        // Negative-stock guard, moving-average valuation, GL posting, audit and outbox all fire
        // exactly as for a hand-entered adjustment. Branch comes from the request context.
        stockService.adjust(new AdjustStockRequest(
                product.uid(), delta, reason, note, null, null));
    }

    private static String describeOutcome(ProductDto product, BigDecimal target,
                                           BigDecimal delta, String costNote) {
        String s = delta.signum() != 0
                ? product.code() + " → " + fmt(target) + " (" + signed(delta) + ")"
                : product.code();
        return costNote == null ? s : s + "; " + costNote;
    }

    /**
     * Set the one-time opening valuation via the ratified path (ADR-0020 D-5b), which writes
     * avg_cost + on_hand_value and posts DR Inventory / CR Opening Balance Equity.
     *
     * <p>Gated on {@code INVENTORY.OPENING.SET} in addition to the sheet's own {@code STOCK.IMPORT}:
     * this leg posts to the GL, so holding mass-import alone must not confer it. Refused rather than
     * silently dropped — a cost the operator typed and believes was applied is worse than an error.
     *
     * <p>Never overwrites an existing valuation: the service rejects an already-valued product, and
     * that refusal is reported as a note instead of failing the row, so one already-priced item does
     * not abort a sheet of hundreds.
     *
     * @return a short outcome note, or null when there was nothing to value
     */
    private CostOutcome applyOpeningCost(Long companyId, ProductDto product, BigDecimal unitCost) {
        if (!permissionResolver.hasPermission(
                RequestContext.get(), PERM_OPENING_SET, System.currentTimeMillis())) {
            throw new IllegalArgumentException(
                    "'" + COL_COST + "' needs the opening-valuation permission. Clear the column, or "
                  + "ask an administrator to grant it.");
        }
        // Re-read: adjust() above may have created the row this values.
        List<StockOnHand> rows = onHands.findAllByCompanyIdAndBranchIdAndProductId(
                companyId, activeBranchId(), product.id());
        if (rows.isEmpty()) {
            return new CostOutcome(false, "no stock here to value");
        }
        if (rows.size() > 1) {
            // Safety net on a GL-posting path. The single-location check above ran BEFORE the
            // adjustment; adjust() corrects a row in place today, so this should not arise — but
            // valuing an arbitrary bin would post the cost to the wrong row, so report instead.
            return new CostOutcome(false, "cost not set (stocked at " + rows.size()
                 + " locations — value it per location)");
        }
        StockOnHand soh = rows.get(0);
        boolean alreadyValued = soh.getAvgCost() != null
                || soh.getOnHandValue().compareTo(BigDecimal.ZERO) != 0;
        if (alreadyValued) {
            return new CostOutcome(false, "cost unchanged (already valued)");
        }
        valuation.setOpeningValue(
                new SetOpeningValuationRequest(soh.getUid(), unitCost), LocalDate.now(BUSINESS_ZONE));
        return new CostOutcome(true, "cost " + fmt(unitCost) + " set");
    }

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        Long branchId = activeBranchId();

        // Current on-hand per product at the active branch, summed across locations — one query.
        Map<Long, BigDecimal> onHandByProduct = new LinkedHashMap<>();
        for (StockOnHand soh : onHands.findByCompanyIdAndBranchId(companyId, branchId, Pageable.unpaged())) {
            onHandByProduct.merge(soh.getProductId(),
                    soh.getQuantity() != null ? soh.getQuantity() : BigDecimal.ZERO, BigDecimal::add);
        }

        return productService.list(companyId, null, Pageable.unpaged()).getContent().stream()
                .filter(p -> p.stockable() && p.status() == MasterStatus.ACTIVE)
                .limit(EXPORT_MAX)
                .map(p -> exportRow(p, onHandByProduct.getOrDefault(p.id(), BigDecimal.ZERO)))
                .toList();
    }

    /** One export row: current on-hand as read-only reference, New On-Hand Qty left blank to fill in. */
    private static LinkedHashMap<String, String> exportRow(ProductDto p, BigDecimal current) {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put(COL_PRODUCT, p.code());
        r.put(COL_NAME, p.name());
        r.put(COL_CURRENT, fmt(current));
        r.put(COL_NEW, "");
        // Left blank like the quantity: a re-upload of an untouched export must change nothing, and
        // a blank cost means "leave the valuation as it is".
        r.put(COL_COST, "");
        r.put(COL_REASON, "");
        r.put(COL_NOTE, "");
        return r;
    }

    /**
     * Resolve an exact product code within the company via the products service (cross-module by
     * DTO, not by entity — {@link StockServiceImpl} uses the same search primitive). The search is a
     * code/name contains, so filter to an exact (case-insensitive) code match.
     */
    private ProductDto resolveProduct(Long companyId, String code) {
        return productService.list(companyId, code, Pageable.unpaged()).getContent().stream()
                .filter(p -> code.equalsIgnoreCase(p.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_PRODUCT + "' '" + code + "' was not found."));
    }

    private Long activeBranchId() {
        RequestContext.Principal p = RequestContext.get();
        if (p == null || p.branchId() == null) {
            throw new IllegalStateException("No active branch in context. Select a branch and retry.");
        }
        return p.branchId();
    }

    /** Trim trailing zeros without scientific notation, e.g. 48.000000 → "48", 2.50 → "2.5". */
    private static String fmt(BigDecimal v) {
        if (v.signum() == 0) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    /** Signed delta for the outcome message, e.g. "+15" / "-3". */
    private static String signed(BigDecimal delta) {
        String n = fmt(delta.abs());
        return (delta.signum() < 0 ? "-" : "+") + n;
    }
}
