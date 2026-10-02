package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceRowDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceTotalsDto;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase Price Variance — goods-receipt lines received in a period whose price differs from the
 * purchase-order price.
 *
 * <h2>Two comparisons</h2>
 * <ol>
 *   <li><b>Receipt cost vs PO price.</b> A placed order's lines are frozen (only a DRAFT order can be
 *       edited) and a receipt line takes its cost from its order line, so in normal operation this is
 *       zero. It is still checked and shown, so that any receipt whose cost disagrees with its order
 *       is surfaced rather than assumed impossible.</li>
 *   <li><b>Bill price vs PO price</b> (only for a caller who may see supplier bills) — the unit price
 *       on the supplier bill lines claimed against this receipt line (bill lines carry the receipt
 *       line they are matched to). This is where variance actually occurs: the supplier invoices at
 *       a different price from the one ordered. Draft bills are excluded (still being entered). Two
 *       bills against one receipt line are combined as a quantity-weighted average price. Bill lines
 *       linked only to the order line, not to a receipt line, are not attributed to any receipt.</li>
 * </ol>
 * A line is listed when either comparison is non-zero. Voided receipts are excluded entirely — a
 * voided receipt no longer carries a price anyone paid.
 *
 * <p>Variance is {@code actual - PO price}: positive means the business paid more than ordered.
 * Totals: receipt variance x received quantity, bill variance x billed quantity, in the company's
 * base currency only.
 */
@Component
@Transactional(readOnly = true)
public class PurchasePriceVarianceQuery {

    private static final int PRICE_SCALE = 4;
    private static final int MONEY_SCALE = 2;
    private static final int PCT_SCALE   = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcTemplate          jdbc;
    private final ScopeGuard            scopeGuard;
    private final BranchReadGuard       branchGuard;
    private final PurchaseReportSupport support;

    public PurchasePriceVarianceQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
            BranchReadGuard branchGuard, PurchaseReportSupport support) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.support     = support;
    }

    /** @param showBills whether the caller may see supplier bills (decided at the controller) */
    public PurchasePriceVarianceDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
            String branchUid, String supplierUid, boolean showBills) {
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

        List<Object> params = new ArrayList<>();
        String billCte;
        String billJoin;
        String billCols;
        String billPredicate;
        if (showBills) {
            params.add(companyId);
            billCte = """
                    WITH bill AS (
                        SELECT sbl.gr_line_uid                          AS gr_line_uid,
                               sbl.currency                             AS currency,
                               SUM(sbl.billed_qty)                      AS billed_qty,
                               SUM(sbl.billed_qty * sbl.unit_cost_amount) AS billed_value
                        FROM supplier_bill_lines sbl
                        JOIN supplier_bills sb ON sb.id = sbl.supplier_bill_id
                        WHERE sbl.company_id = ?
                          AND sb.status <> 'DRAFT'
                          AND sbl.gr_line_uid IS NOT NULL
                        GROUP BY sbl.gr_line_uid, sbl.currency
                    )
                    """;
            billJoin = " LEFT JOIN bill ON bill.gr_line_uid = grl.uid AND bill.currency = grl.currency";
            billCols = ", bill.billed_qty AS billed_qty, bill.billed_value AS billed_value";
            // Leading space is load-bearing: this is appended straight after the receipt comparison.
            billPredicate = " OR (bill.billed_qty > 0"
                    + " AND ROUND(bill.billed_value / bill.billed_qty, 4) <> pol.unit_cost_amount)";
        } else {
            billCte = "";
            billJoin = "";
            billCols = ", CAST(NULL AS NUMERIC) AS billed_qty, CAST(NULL AS NUMERIC) AS billed_value";
            billPredicate = "";
        }
        params.add(companyId);
        params.add(from);
        params.add(to);
        StringBuilder filterSql = new StringBuilder();
        if (branch != null) {
            filterSql.append(" AND gr.branch_id = ?");
            params.add(branch.id());
        }
        if (supplier != null) {
            filterSql.append(" AND gr.supplier_id = ?");
            params.add(supplier.id());
        }

        String sql = billCte + """
                SELECT gr.received_at        AS received_at,
                       gr.receipt_number     AS receipt_number,
                       gr.uid                AS gr_uid,
                       po.order_number       AS order_number,
                       s.code                AS supplier_code,
                       s.display_name        AS supplier_name,
                       grl.product_code      AS product_code,
                       grl.product_name      AS product_name,
                       grl.unit_name         AS unit_name,
                       grl.received_qty      AS received_qty,
                       pol.unit_cost_amount  AS po_price,
                       grl.unit_cost_amount  AS receipt_cost,
                       grl.currency          AS currency""" + billCols + """

                FROM goods_receipt_lines grl
                JOIN goods_receipts gr        ON gr.id  = grl.goods_receipt_id
                JOIN purchase_order_lines pol ON pol.id = grl.purchase_order_line_id
                LEFT JOIN purchase_orders po  ON po.id  = gr.purchase_order_id
                LEFT JOIN suppliers s         ON s.id   = gr.supplier_id""" + billJoin + """

                WHERE gr.company_id = ?
                  AND gr.status = 'RECEIVED'
                  AND gr.received_at >= ? AND gr.received_at < ?""" + filterSql + """

                  AND (grl.unit_cost_amount <> pol.unit_cost_amount""" + billPredicate + """
                )
                ORDER BY gr.received_at, gr.receipt_number, grl.line_no
                """;

        List<PurchasePriceVarianceRowDto> rows = jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal qty         = rs.getBigDecimal("received_qty");
            BigDecimal poPrice     = rs.getBigDecimal("po_price");
            BigDecimal receiptCost = rs.getBigDecimal("receipt_cost");
            BigDecimal rcvPerUnit  = receiptCost.subtract(poPrice);
            BigDecimal rcvTotal    = rcvPerUnit.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            BigDecimal billedQty   = rs.getBigDecimal("billed_qty");
            BigDecimal billedValue = rs.getBigDecimal("billed_value");
            BigDecimal billPrice = null;
            BigDecimal billPerUnit = null;
            BigDecimal billTotal = null;
            BigDecimal billPct = null;
            if (showBills && billedQty != null && billedQty.signum() > 0) {
                billPrice   = billedValue.divide(billedQty, PRICE_SCALE, RoundingMode.HALF_UP);
                billPerUnit = billPrice.subtract(poPrice);
                // Exact: what was billed less what the same quantity would have cost at PO price.
                billTotal   = billedValue.subtract(billedQty.multiply(poPrice))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                billPct     = pct(billPerUnit, poPrice);
            }
            Timestamp at = rs.getTimestamp("received_at");
            return new PurchasePriceVarianceRowDto(
                    at != null ? at.toInstant().atZone(company.zone()).toOffsetDateTime().toString() : null,
                    rs.getString("receipt_number"),
                    rs.getString("gr_uid"),
                    rs.getString("order_number"),
                    rs.getString("supplier_code"),
                    rs.getString("supplier_name"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("unit_name"),
                    qty, poPrice, receiptCost,
                    rcvPerUnit, rcvTotal, pct(rcvPerUnit, poPrice),
                    showBills ? billedQty : null,
                    billPrice, billPerUnit, billTotal, billPct,
                    rs.getString("currency"));
        }, params.toArray());

        String base = company.baseCurrency();
        BigDecimal rcvSum  = BigDecimal.ZERO;
        BigDecimal billSum = BigDecimal.ZERO;
        long otherCcy = 0;
        for (PurchasePriceVarianceRowDto r : rows) {
            if (!base.equals(r.currency())) {
                otherCcy++;
                continue;
            }
            rcvSum = rcvSum.add(r.receiptVarianceTotal());
            if (r.billVarianceTotal() != null) {
                billSum = billSum.add(r.billVarianceTotal());
            }
        }

        return new PurchasePriceVarianceDto(
                company.header(), fromDate.toString(), toDate.toString(),
                branch != null ? branch.name() : null,
                supplier != null ? supplier.name() : null,
                base, showBills, rows,
                new PurchasePriceVarianceTotalsDto(rows.size(), rcvSum,
                        showBills ? billSum : null, otherCcy),
                Instant.now().toString());
    }

    /** Variance as a percentage of the PO price; null when the PO price is zero (no base). */
    private static BigDecimal pct(BigDecimal perUnit, BigDecimal poPrice) {
        if (poPrice == null || poPrice.signum() == 0) {
            return null;
        }
        return perUnit.multiply(HUNDRED).divide(poPrice, PCT_SCALE, RoundingMode.HALF_UP);
    }
}
