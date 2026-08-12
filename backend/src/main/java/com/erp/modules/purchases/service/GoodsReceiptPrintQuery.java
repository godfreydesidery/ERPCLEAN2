package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintLineDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptVatBandDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the printed vendor Goods Received Note (Kilimanjaro K9, 2026-08-12).
 *
 * <p>The receipt tables hold code, description, quantity, unit cost and line amount. The note the
 * client signs also shows the SELLING price, the PREVIOUS cost and the margin between them, plus a
 * VAT band summary and a "prepared by". None of that is stored on the receipt, and none of it needs
 * to be: every figure is derivable from data the system already keeps, so this reads it at print
 * time rather than adding columns to a frozen schema.
 *
 * <p><b>Selling price</b> comes from the company's default price list, resolved with the same
 * fallback chain the Product Master and the stock reports use (flagged default → a list coded
 * DEFAULT/STANDARD → the only ACTIVE list) — a strict {@code is_default = true} lookup returns
 * nothing for most companies, and a GRN with a blank SP column on a shop whose till prices perfectly
 * well would read as a fault. The base-unit row wins (ADR-0048 D-1); a product priced only by the
 * pack is divided by {@code factor_to_base} so the column stays per-unit and comparable with the
 * cost beside it. Only prices in the RECEIPT's currency are read — a USD price next to a TZS cost
 * would produce a margin that is arithmetically fine and completely meaningless.
 *
 * <p><b>Last cost price</b> is the unit cost on the most recent EARLIER receipt of the same product
 * — earlier by {@code received_at}, ties broken by line id, VOID receipts excluded, and this receipt
 * excluded, so reprinting the note a month later still shows what the cost was BEFORE this delivery.
 * That is the whole value of the column: it is the number the buyer checks the new price against.
 * Not {@code stock_on_hand.avg_cost}, which is a blended moving average and would make a price rise
 * look smaller than it is.
 *
 * <p><b>VAT</b> is expected input VAT, derived from each product's VAT status against the company's
 * {@code tax_rates}. The receipt posts no VAT — purchase VAT belongs to the supplier bill — so this
 * is a check figure for the clerk matching the delivery to the invoice, never a posting.
 *
 * <p>Cross-module reads (products, price lists, suppliers, branches, users, tax rates) are scalar
 * native-SQL joins, the same pattern as {@code ProductStockReportQuery} — no entity or service is
 * imported across a module boundary.
 */
@Component
@Transactional(readOnly = true)
public class GoodsReceiptPrintQuery {

    /** Money scale for the derived VAT figures. Line amounts are stored at 4 dp, printed at 2. */
    private static final int MONEY_SCALE = 2;

    /**
     * Margin is a display percentage at 2 dp. HALF_EVEN, not HALF_UP: it is the rounding the client's
     * existing note uses (a 15.625 margin prints 15.62 there, and 19.0476 prints 19.05), and a
     * margin column that disagrees with the document it is replacing invites a bug report on day one.
     */
    private static final int MARGIN_SCALE = 2;

    /** Intermediate precision for {@code (sp − cp) / sp} before it is scaled to a percentage. */
    private static final int MARGIN_WORKING_SCALE = 8;

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public GoodsReceiptPrintQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    // -------------------------------------------------------------------------

    public GoodsReceiptPrintDto byUid(String uid) {
        Header h = loadHeader(uid);
        if (h == null) {
            throw new NotFoundException("Goods receipt not found.");
        }
        scopeGuard.assertCanActIn(RequestContext.get(), h.companyId());

        Long priceListId = resolveDefaultPriceListId(h.companyId());
        List<GoodsReceiptPrintLineDto> lines = loadLines(h, priceListId);

        List<GoodsReceiptVatBandDto> bands = vatBands(loadLineTax(h.id()));

        BigDecimal net = sum(lines.stream().map(GoodsReceiptPrintLineDto::amount).toList())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal vat = sum(bands.stream().map(GoodsReceiptVatBandDto::vatAmount).toList())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal rounding = BigDecimal.ZERO.setScale(MONEY_SCALE);

        return new GoodsReceiptPrintDto(
                h.uid(), h.companyId(), h.receiptNumber(), h.status(), h.receivedAt(),
                h.orderNumber(), h.supplierName(), h.supplierTin(), supplierAddress(h),
                h.branchName(), h.currency(), h.notes(), h.preparedByName(),
                lines, bands,
                net, vat, rounding, net.add(vat).add(rounding));
    }

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    private record Header(Long id, String uid, Long companyId, String receiptNumber, String status,
                          java.time.Instant receivedAt, String orderNumber, String currency,
                          String notes, String branchName, String supplierName, String supplierTin,
                          String supplierAddress, String supplierRegion, String supplierDistrict,
                          String supplierCountry, String preparedByName) {}

    private Header loadHeader(String uid) {
        return jdbc.query(
                """
                SELECT gr.id, gr.uid, gr.company_id, gr.receipt_number, gr.status, gr.received_at,
                       gr.notes,
                       po.order_number,
                       po.currency          AS po_currency,
                       b.name               AS branch_name,
                       s.display_name       AS supplier_name,
                       s.tin                AS supplier_tin,
                       s.physical_address   AS supplier_address,
                       s.region             AS supplier_region,
                       s.district           AS supplier_district,
                       s.country            AS supplier_country,
                       u.display_name       AS prepared_by
                FROM goods_receipts gr
                LEFT JOIN purchase_orders po ON po.id = gr.purchase_order_id
                LEFT JOIN branches  b ON b.id = gr.branch_id
                LEFT JOIN suppliers s ON s.id = gr.supplier_id
                LEFT JOIN app_users u ON u.id = gr.received_by
                WHERE gr.uid = ?
                """,
                (ResultSetExtractor<Header>) rs -> rs.next()
                        ? new Header(
                                rs.getLong("id"),
                                rs.getString("uid"),
                                rs.getLong("company_id"),
                                rs.getString("receipt_number"),
                                rs.getString("status"),
                                rs.getTimestamp("received_at") != null
                                        ? rs.getTimestamp("received_at").toInstant() : null,
                                rs.getString("order_number"),
                                rs.getString("po_currency"),
                                rs.getString("notes"),
                                rs.getString("branch_name"),
                                rs.getString("supplier_name"),
                                rs.getString("supplier_tin"),
                                rs.getString("supplier_address"),
                                rs.getString("supplier_region"),
                                rs.getString("supplier_district"),
                                rs.getString("supplier_country"),
                                rs.getString("prepared_by"))
                        : null,
                uid);
    }

    private static List<String> supplierAddress(Header h) {
        List<String> out = new ArrayList<>();
        addIfPresent(out, h.supplierAddress());
        addIfPresent(out, joinNonBlank(h.supplierDistrict(), h.supplierRegion()));
        addIfPresent(out, h.supplierCountry());
        return List.copyOf(out);
    }

    private static void addIfPresent(List<String> out, String s) {
        if (s != null && !s.isBlank()) {
            out.add(s.trim());
        }
    }

    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) return a.trim() + ", " + b.trim();
        if (hasA) return a.trim();
        return hasB ? b.trim() : null;
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    private List<GoodsReceiptPrintLineDto> loadLines(Header h, Long priceListId) {
        String sql = """
                WITH sell AS (
                    SELECT pp.product_id,
                           MIN(CASE WHEN pp.unit_id IS NULL THEN pp.amount END) AS base_price,
                           MIN(CASE WHEN pp.unit_id IS NOT NULL AND bp.factor_to_base > 0
                                    THEN pp.amount / bp.factor_to_base END)     AS pack_unit_price
                    FROM product_prices pp
                    LEFT JOIN product_bulk_packs bp
                           ON bp.product_id = pp.product_id AND bp.unit_id = pp.unit_id
                    WHERE pp.company_id    = ?
                      AND pp.price_list_id = CAST(? AS BIGINT)
                      AND pp.currency      = ?
                    GROUP BY pp.product_id
                ),
                prior AS (
                    SELECT DISTINCT ON (pl.product_id)
                           pl.product_id,
                           pl.unit_cost_amount
                    FROM goods_receipt_lines pl
                    JOIN goods_receipts pr ON pr.id = pl.goods_receipt_id
                    WHERE pl.company_id = ?
                      AND pr.status     = 'RECEIVED'
                      AND pr.id        <> ?
                      AND (pr.received_at < CAST(? AS TIMESTAMPTZ)
                           OR (pr.received_at = CAST(? AS TIMESTAMPTZ) AND pr.id < ?))
                    ORDER BY pl.product_id, pr.received_at DESC NULLS LAST, pl.id DESC
                )
                SELECT grl.line_no,
                       grl.product_code,
                       grl.product_name,
                       grl.received_qty,
                       grl.unit_name,
                       grl.unit_cost_amount,
                       grl.line_cost_amount,
                       COALESCE(sell.base_price, sell.pack_unit_price) AS selling_price,
                       prior.unit_cost_amount                          AS last_cost,
                       p.vat_status
                FROM goods_receipt_lines grl
                JOIN products p        ON p.id = grl.product_id
                LEFT JOIN sell         ON sell.product_id  = grl.product_id
                LEFT JOIN prior        ON prior.product_id = grl.product_id
                WHERE grl.goods_receipt_id = ?
                ORDER BY grl.line_no
                """;

        java.sql.Timestamp receivedAt = h.receivedAt() != null
                ? java.sql.Timestamp.from(h.receivedAt())
                : null;

        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal cost    = rs.getBigDecimal("unit_cost_amount");
            BigDecimal selling = rs.getBigDecimal("selling_price");
            return new GoodsReceiptPrintLineDto(
                    rs.getShort("line_no"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getBigDecimal("received_qty"),
                    rs.getString("unit_name"),
                    cost,
                    selling,
                    rs.getBigDecimal("last_cost"),
                    marginPercent(cost, selling),
                    rs.getBigDecimal("line_cost_amount"),
                    rs.getString("vat_status"));
        },
        h.companyId(), priceListId, h.currency(),
        h.companyId(), h.id(), receivedAt, receivedAt, h.id(),
        h.id());
    }

    /**
     * Margin on the SELLING price — {@code (sp − cp) / sp × 100} — which is the convention on the
     * note this replaces (13,000 cost against a 15,000 price prints 13.33, not the 15.38 a
     * mark-up-on-cost reading would give). Null when there is no selling price, or it is zero: a
     * blank says "nobody has priced this yet", a 0.00 or a 100.00 would be a claim about the shop's
     * margin that nothing supports.
     */
    static BigDecimal marginPercent(BigDecimal cost, BigDecimal selling) {
        if (cost == null || selling == null || selling.signum() == 0) {
            return null;
        }
        return selling.subtract(cost)
                .divide(selling, MARGIN_WORKING_SCALE, RoundingMode.HALF_EVEN)
                .movePointRight(2)
                .setScale(MARGIN_SCALE, RoundingMode.HALF_EVEN);
    }

    // -------------------------------------------------------------------------
    // VAT bands
    // -------------------------------------------------------------------------

    /**
     * (vat_status, configured rate, line amount) for every line on the receipt.
     * Package-private so {@link #vatBands} can be exercised without a database.
     */
    record LineTax(String vatStatus, BigDecimal rate, BigDecimal amount) {}

    private List<LineTax> loadLineTax(Long receiptId) {
        return jdbc.query(
                """
                SELECT p.vat_status,
                       COALESCE(tr.rate, 0) AS rate,
                       grl.line_cost_amount
                FROM goods_receipt_lines grl
                JOIN products p ON p.id = grl.product_id
                LEFT JOIN tax_rates tr
                       ON tr.company_id = grl.company_id
                      AND tr.vat_status = p.vat_status
                      AND tr.status     = 'ACTIVE'
                WHERE grl.goods_receipt_id = ?
                ORDER BY grl.line_no
                """,
                (rs, rowNum) -> new LineTax(
                        rs.getString("vat_status"),
                        rs.getBigDecimal("rate"),
                        rs.getBigDecimal("line_cost_amount")),
                receiptId);
    }

    /**
     * Groups the lines into one band per VAT status, in first-appearance order so the foot of the
     * note reads in the same order as the lines above it. VAT is computed on the BAND total, not
     * summed per line, so the printed VAT is what a reader recomputing {@code goods value × rate}
     * gets — per-line rounding then adding would drift by a shilling or two and cost somebody an
     * afternoon.
     *
     * <p>Package-private and static so the grouping and the rounding are testable without a database.
     */
    static List<GoodsReceiptVatBandDto> vatBands(List<LineTax> lines) {
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();  // status -> [rate, goodsValue]
        for (LineTax l : lines) {
            String status  = l.vatStatus() != null ? l.vatStatus() : "";
            BigDecimal rate = l.rate() != null ? l.rate() : BigDecimal.ZERO;
            BigDecimal amt  = l.amount() != null ? l.amount() : BigDecimal.ZERO;
            BigDecimal[] band = acc.computeIfAbsent(status, k -> new BigDecimal[]{rate, BigDecimal.ZERO});
            band[1] = band[1].add(amt);
        }
        List<GoodsReceiptVatBandDto> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : acc.entrySet()) {
            BigDecimal rate  = e.getValue()[0];
            BigDecimal goods = e.getValue()[1].setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            out.add(new GoodsReceiptVatBandDto(
                    e.getKey(), rate, goods,
                    goods.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }
        return List.copyOf(out);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v != null) {
                total = total.add(v);
            }
        }
        return total;
    }

    /**
     * The company's default price list — same fallback chain as {@code ProductStockReportQuery}, so
     * the GRN's selling price and the Product List's selling price can never come from two different
     * lists and start an argument. Null when nobody has decided, which prints a blank SP column
     * rather than a price from an arbitrary list.
     */
    private Long resolveDefaultPriceListId(Long companyId) {
        return jdbc.query(
                """
                WITH active AS (
                    SELECT id, code, is_default, COUNT(*) OVER () AS n
                    FROM price_lists
                    WHERE company_id = ? AND status = 'ACTIVE'
                )
                SELECT id
                FROM active
                WHERE is_default
                   OR upper(code) IN ('DEFAULT', 'STANDARD')
                   OR n = 1
                ORDER BY is_default DESC,
                         CASE WHEN upper(code) IN ('DEFAULT', 'STANDARD') THEN 0 ELSE 1 END,
                         id
                LIMIT 1
                """,
                (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong("id") : null,
                companyId);
    }
}
