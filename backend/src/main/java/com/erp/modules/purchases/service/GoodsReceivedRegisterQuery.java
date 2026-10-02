package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterDto;
import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterRowDto;
import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterTotalsDto;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods Received Register — one row per goods-receipt line over a period, direct receipts
 * ("Receive Without Order") included.
 *
 * <h2>Voided receipts</h2>
 * Shown the way the Stock Movement report shows them, so the two reports agree: the receipt stays
 * on the day it was received, and a separate {@code VOID} entry with NEGATED quantity and value
 * appears on the day it was voided. A period's total is therefore the net value received in that
 * period, and a register already printed for a closed month never changes after the fact. Purchase
 * returns are NOT netted here — the register lists what came in; returns are on Purchases by
 * Supplier.
 *
 * <h2>Value</h2>
 * {@code quantity x unit cost} as recorded on the receipt, excluding VAT. A receipt stores no VAT:
 * purchase VAT belongs to the supplier bill (the printed GRN only derives a check figure), so the
 * register does not invent one. Landed cost allocated afterwards is not included.
 *
 * <p>Paged: a busy branch receives thousands of lines a year. {@code totals} cover the whole
 * matching set. Totals are in the company's base currency; a foreign-currency line is listed in its
 * own currency and counted in {@code rowsInOtherCurrency} instead of being added to the total.
 */
@Component
@Transactional(readOnly = true)
public class GoodsReceivedRegisterQuery {

    public static final int MAX_PAGE_SIZE     = 500;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_EXPORT_ROWS   = 20_000;

    private final JdbcTemplate          jdbc;
    private final ScopeGuard            scopeGuard;
    private final BranchReadGuard       branchGuard;
    private final PurchaseReportSupport support;

    public GoodsReceivedRegisterQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
            BranchReadGuard branchGuard, PurchaseReportSupport support) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.support     = support;
    }

    /** One page of the register. */
    public GoodsReceivedRegisterDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
            String branchUid, String supplierUid, String productUid, int page, int size) {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return build(companyId, fromDate, toDate, branchUid, supplierUid, productUid,
                Math.max(page, 0), safeSize);
    }

    /** The whole register for a download, refused (friendly) past {@link #MAX_EXPORT_ROWS}. */
    public GoodsReceivedRegisterDto reportForExport(Long companyId, LocalDate fromDate,
            LocalDate toDate, String branchUid, String supplierUid, String productUid) {
        GoodsReceivedRegisterDto dto = build(companyId, fromDate, toDate, branchUid, supplierUid,
                productUid, 0, MAX_EXPORT_ROWS);
        if (dto.totalElements() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    "This report is too large to export in one file. Please choose a shorter date "
                    + "range, or filter by branch, supplier or product, and try again.");
        }
        return dto;
    }

    // -------------------------------------------------------------------------

    private GoodsReceivedRegisterDto build(Long companyId, LocalDate fromDate, LocalDate toDate,
            String branchUid, String supplierUid, String productUid, int page, int size) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        PurchaseReportSupport.requirePeriod(fromDate, toDate);

        PurchaseReportSupport.Company company = support.loadCompany(companyId);
        OffsetDateTime from = company.startOf(fromDate);
        OffsetDateTime to   = company.endOf(toDate);

        PurchaseReportSupport.Ref branch =
                support.resolve("branches", "name", branchUid, companyId, "Branch");
        branchGuard.assertMayRead(principal, branch != null ? branch.id() : null);
        PurchaseReportSupport.Ref supplier =
                support.resolve("suppliers", "display_name", supplierUid, companyId, "Supplier");
        PurchaseReportSupport.Ref product =
                support.resolve("products", "name", productUid, companyId, "Product");

        StringBuilder filterSql = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();
        if (branch != null) {
            filterSql.append(" AND gr.branch_id = ?");
            filterParams.add(branch.id());
        }
        if (supplier != null) {
            filterSql.append(" AND gr.supplier_id = ?");
            filterParams.add(supplier.id());
        }
        if (product != null) {
            filterSql.append(" AND grl.product_id = ?");
            filterParams.add(product.id());
        }

        // Textual order: receipt half (company, window, filters), then void half (same again).
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        params.addAll(filterParams);
        params.add(companyId);
        params.add(from);
        params.add(to);
        params.addAll(filterParams);

        String cte = entriesCte(filterSql.toString());

        GoodsReceivedRegisterTotalsDto totals = queryTotals(cte, params, company.baseCurrency());
        List<GoodsReceivedRegisterRowDto> rows = queryPage(cte, params, page, size, company.zone());

        long totalElements = totals.lines();
        int totalPages = size > 0 ? (int) Math.ceil(totalElements / (double) size) : 0;

        return new GoodsReceivedRegisterDto(
                company.header(),
                fromDate.toString(), toDate.toString(),
                branch != null ? branch.name() : null,
                supplier != null ? supplier.name() : null,
                product != null ? product.name() : null,
                company.baseCurrency(),
                rows, totals,
                page, size, totalElements, totalPages,
                Instant.now().toString());
    }

    /**
     * Receipt entries (+) UNION ALL void entries (-). A VOID receipt keeps its received_at, so its
     * original receipt still belongs to the period it was received in; the reversal is dated by
     * voided_at. A DRAFT receipt was never received and appears in neither half.
     */
    private static String entriesCte(String filterSql) {
        String select = """
                SELECT %s                       AS entry_type,
                       %s                       AS entry_at,
                       %s                       AS sign,
                       %s                       AS kind_order,
                       gr.id                    AS gr_id,
                       gr.uid                   AS gr_uid,
                       gr.receipt_number        AS receipt_number,
                       gr.purchase_order_id     AS po_id,
                       gr.supplier_id           AS supplier_id,
                       gr.branch_id             AS branch_id,
                       grl.line_no              AS line_no,
                       grl.product_code         AS product_code,
                       grl.product_name         AS product_name,
                       grl.unit_name            AS unit_name,
                       grl.received_qty         AS received_qty,
                       grl.unit_cost_amount     AS unit_cost,
                       grl.line_cost_amount     AS line_cost,
                       grl.currency             AS currency
                FROM goods_receipt_lines grl
                JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
                WHERE gr.company_id = ?
                """;
        String receipts = select.formatted("'RECEIPT'", "gr.received_at", "1", "0")
                + "  AND gr.status IN ('RECEIVED','VOID')\n"
                + "  AND gr.received_at >= ? AND gr.received_at < ?" + filterSql;
        String voids = select.formatted("'VOID'", "gr.voided_at", "-1", "1")
                + "  AND gr.status = 'VOID'\n"
                + "  AND gr.voided_at >= ? AND gr.voided_at < ?" + filterSql;
        return "WITH e AS (\n" + receipts + "\nUNION ALL\n" + voids + "\n)\n";
    }

    private GoodsReceivedRegisterTotalsDto queryTotals(String cte, List<Object> params,
                                                       String baseCurrency) {
        // Bind order follows the statement text: the CTE's binds first, then the two currency
        // comparisons in the SELECT list.
        String sql = cte + """
                SELECT COUNT(DISTINCT gr_id) FILTER (WHERE entry_type = 'RECEIPT') AS receipts,
                       COUNT(DISTINCT gr_id) FILTER (WHERE entry_type = 'VOID')    AS voids,
                       COUNT(*)                                                    AS lines,
                       COALESCE(SUM(sign * line_cost) FILTER (WHERE currency = ?), 0) AS value,
                       COUNT(*) FILTER (WHERE currency <> ?)                       AS other_ccy
                FROM e
                """;
        List<Object> ordered = new ArrayList<>(params);
        ordered.add(baseCurrency);
        ordered.add(baseCurrency);
        return jdbc.queryForObject(sql,
                (rs, rowNum) -> new GoodsReceivedRegisterTotalsDto(
                        rs.getLong("receipts"),
                        rs.getLong("voids"),
                        rs.getLong("lines"),
                        rs.getBigDecimal("value"),
                        rs.getLong("other_ccy")),
                ordered.toArray());
    }

    private List<GoodsReceivedRegisterRowDto> queryPage(String cte, List<Object> params,
                                                        int page, int size, ZoneId zone) {
        String sql = cte + """
                SELECT e.*,
                       po.order_number          AS order_number,
                       po.origin                AS po_origin,
                       s.code                   AS supplier_code,
                       s.display_name           AS supplier_name,
                       b.name                   AS branch_name
                FROM e
                LEFT JOIN purchase_orders po ON po.id = e.po_id
                LEFT JOIN suppliers       s  ON s.id  = e.supplier_id
                LEFT JOIN branches        b  ON b.id  = e.branch_id
                ORDER BY e.entry_at, e.receipt_number, e.kind_order, e.line_no
                LIMIT ? OFFSET ?
                """;
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        return jdbc.query(sql,
                (rs, rowNum) -> {
                    BigDecimal sign = BigDecimal.valueOf(rs.getInt("sign"));
                    Timestamp at = rs.getTimestamp("entry_at");
                    return new GoodsReceivedRegisterRowDto(
                            rs.getString("entry_type"),
                            at != null ? at.toInstant().atZone(zone).toOffsetDateTime().toString()
                                       : null,
                            rs.getString("receipt_number"),
                            rs.getString("gr_uid"),
                            rs.getString("order_number"),
                            "DIRECT_RECEIPT".equals(rs.getString("po_origin")),
                            rs.getString("supplier_code"),
                            rs.getString("supplier_name"),
                            rs.getString("branch_name"),
                            rs.getString("product_code"),
                            rs.getString("product_name"),
                            rs.getString("unit_name"),
                            rs.getBigDecimal("received_qty").multiply(sign),
                            rs.getBigDecimal("unit_cost"),
                            rs.getBigDecimal("line_cost").multiply(sign),
                            rs.getString("currency"));
                },
                p.toArray());
    }
}
