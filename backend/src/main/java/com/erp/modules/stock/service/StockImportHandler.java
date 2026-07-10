package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
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
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
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
 * can be granted mass import without manual adjust, or vice-versa.
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
    private static final String COL_REASON  = "Reason";
    private static final String COL_NOTE    = "Note";

    private final StockService stockService;
    private final ProductService productService;
    private final StockOnHandRepository onHands;

    public StockImportHandler(StockService stockService,
                              ProductService productService,
                              StockOnHandRepository onHands) {
        this.stockService = stockService;
        this.productService = productService;
        this.onHands = onHands;
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
                ColumnSpec.choice(COL_REASON, false,
                        "Adjustment reason for the difference. Blank = COUNT_CORRECTION.", reasons),
                ColumnSpec.of(COL_NOTE, false, "Optional note recorded on the adjustment."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        BigDecimal target = ImportParsers.parseDecimal(row, COL_NEW);
        if (target == null) {
            return RowOutcome.skip(row.rowNumber(), row.get(COL_PRODUCT),
                    "No new on-hand quantity entered — left unchanged.");
        }
        if (target.signum() < 0) {
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
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return RowOutcome.skip(row.rowNumber(), product.code(),
                    "Already at " + fmt(target) + " — no change.");
        }

        AdjustmentReason reason = ImportParsers.parseEnum(
                AdjustmentReason.class, row, COL_REASON, AdjustmentReason.COUNT_CORRECTION);
        String note = ImportParsers.text(row, COL_NOTE);

        // Route through the SAME service as the single Adjust screen: negative-stock guard,
        // moving-average valuation, GL posting, audit and outbox all fire. Branch comes from context.
        stockService.adjust(new AdjustStockRequest(
                product.uid(), delta, reason, note, null, null));

        return RowOutcome.update(row.rowNumber(),
                product.code() + " → " + fmt(target) + " (" + signed(delta) + ")");
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
