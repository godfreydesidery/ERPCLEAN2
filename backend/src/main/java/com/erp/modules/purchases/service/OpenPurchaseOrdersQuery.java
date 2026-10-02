package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.OpenPurchaseOrderRowDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrdersDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrdersTotalsDto;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open Purchase Orders — placed orders still waiting for goods, line by line, AS AT a date.
 *
 * <h2>What counts as open on the as-of date</h2>
 * <ul>
 *   <li>The order had been PLACED by then ({@code ordered_at} on or before the date). Placing an
 *       order is what passes the approval gate — an order over the approval threshold cannot leave
 *       DRAFT until it is approved — so every placed order is an approved one. Drafts and orders
 *       still awaiting approval are never open.</li>
 *   <li>It had not been CLOSED or VOIDED by then ({@code closed_at} / {@code voided_at} after the
 *       date, or never). This is what makes the as-of date honest: an order closed last week is
 *       still listed in a report run as at last month.</li>
 *   <li>The line still had quantity outstanding: ordered less what had been received by that date,
 *       counting a receipt that was voided only after that date as still received then.</li>
 *   <li>Orders raised automatically behind a "Receive Without Order" receipt are left out. Nobody is
 *       going to deliver against them — they exist only to anchor a receipt — and if that receipt is
 *       later voided the order would otherwise reappear here as goods the supplier owes.</li>
 * </ul>
 *
 * <p>Quantities are shown in the unit the line was ordered in; outstanding value is
 * {@code outstanding x unit price}, excluding VAT, rounded to 2 dp per line so the column adds up to
 * the printed total. Totals are in the company's base currency only.
 */
@Component
@Transactional(readOnly = true)
public class OpenPurchaseOrdersQuery {

    private static final int QTY_SCALE   = 6;
    private static final int MONEY_SCALE = 2;

    private final JdbcTemplate          jdbc;
    private final ScopeGuard            scopeGuard;
    private final BranchReadGuard       branchGuard;
    private final PurchaseReportSupport support;

    public OpenPurchaseOrdersQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
            BranchReadGuard branchGuard, PurchaseReportSupport support) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.support     = support;
    }

    /** @param asOfDate the report date; null means today in the company's time zone */
    public OpenPurchaseOrdersDto report(Long companyId, LocalDate asOfDate, String branchUid,
                                        String supplierUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        PurchaseReportSupport.Company company = support.loadCompany(companyId);
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now(company.zone());
        OffsetDateTime asOfEnd = company.endOf(asOf);

        PurchaseReportSupport.Ref branch =
                support.resolve("branches", "name", branchUid, companyId, "Branch");
        branchGuard.assertMayRead(principal, branch != null ? branch.id() : null);
        PurchaseReportSupport.Ref supplier =
                support.resolve("suppliers", "display_name", supplierUid, companyId, "Supplier");

        List<Object> params = new ArrayList<>();
        params.add(companyId);   // rcv
        params.add(asOfEnd);
        params.add(asOfEnd);
        params.add(companyId);   // po
        params.add(asOfEnd);
        params.add(asOfEnd);
        params.add(asOfEnd);
        StringBuilder filterSql = new StringBuilder();
        if (branch != null) {
            filterSql.append(" AND po.branch_id = ?");
            params.add(branch.id());
        }
        if (supplier != null) {
            filterSql.append(" AND po.supplier_id = ?");
            params.add(supplier.id());
        }

        String sql = """
                WITH rcv AS (
                    SELECT grl.purchase_order_line_id AS pol_id,
                           SUM(grl.qty_in_base)       AS qty_base
                    FROM goods_receipt_lines grl
                    JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
                    WHERE gr.company_id = ?
                      AND gr.received_at < ?
                      AND (gr.status = 'RECEIVED'
                           OR (gr.status = 'VOID' AND gr.voided_at >= ?))
                    GROUP BY grl.purchase_order_line_id
                )
                SELECT po.uid                    AS po_uid,
                       po.order_number           AS order_number,
                       po.ordered_at             AS ordered_at,
                       po.expected_date          AS po_expected,
                       pol.required_by_date      AS line_required_by,
                       s.code                    AS supplier_code,
                       s.display_name            AS supplier_name,
                       b.name                    AS branch_name,
                       pol.product_code          AS product_code,
                       pol.product_name          AS product_name,
                       pol.unit_name             AS unit_name,
                       pol.ordered_qty           AS ordered_qty,
                       pol.ordered_qty_in_base   AS ordered_base,
                       COALESCE(rcv.qty_base, 0) AS received_base,
                       pol.unit_cost_amount      AS unit_cost,
                       pol.currency              AS currency
                FROM purchase_order_lines pol
                JOIN purchase_orders po ON po.id = pol.purchase_order_id
                LEFT JOIN rcv            ON rcv.pol_id = pol.id
                LEFT JOIN suppliers s    ON s.id = po.supplier_id
                LEFT JOIN branches  b    ON b.id = po.branch_id
                WHERE po.company_id = ?
                  AND po.status <> 'DRAFT'
                  AND po.origin = 'MANUAL'
                  AND po.ordered_at < ?
                  AND (po.closed_at IS NULL OR po.closed_at >= ?)
                  AND (po.voided_at IS NULL OR po.voided_at >= ?)
                  AND pol.ordered_qty_in_base > COALESCE(rcv.qty_base, 0)""" + filterSql + """

                ORDER BY po.ordered_at, po.order_number, pol.line_no
                """;

        String base = company.baseCurrency();
        List<OpenPurchaseOrderRowDto> rows = jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal orderedQty  = rs.getBigDecimal("ordered_qty");
            BigDecimal orderedBase = rs.getBigDecimal("ordered_base");
            BigDecimal receivedBase = rs.getBigDecimal("received_base");
            // Base units back to the ORDER unit: received x ordered / ordered_in_base.
            BigDecimal receivedQty = orderedBase.signum() > 0
                    ? receivedBase.multiply(orderedQty).divide(orderedBase, QTY_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal outstanding = orderedQty.subtract(receivedQty);
            BigDecimal unitCost    = rs.getBigDecimal("unit_cost");
            BigDecimal value = outstanding.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            Timestamp orderedAt = rs.getTimestamp("ordered_at");
            LocalDate orderDate = orderedAt != null
                    ? orderedAt.toInstant().atZone(company.zone()).toLocalDate() : null;
            Date lineReq = rs.getDate("line_required_by");
            Date poExp   = rs.getDate("po_expected");
            LocalDate expected = lineReq != null ? lineReq.toLocalDate()
                    : poExp != null ? poExp.toLocalDate() : null;

            return new OpenPurchaseOrderRowDto(
                    rs.getString("order_number"),
                    rs.getString("po_uid"),
                    orderDate != null ? orderDate.toString() : null,
                    expected != null ? expected.toString() : null,
                    rs.getString("supplier_code"),
                    rs.getString("supplier_name"),
                    rs.getString("branch_name"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("unit_name"),
                    orderedQty,
                    receivedQty,
                    outstanding,
                    unitCost,
                    value,
                    rs.getString("currency"),
                    orderDate != null ? Math.max(0, ChronoUnit.DAYS.between(orderDate, asOf)) : 0,
                    expected != null && expected.isBefore(asOf));
        }, params.toArray());

        Set<String> orders = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        long otherCcy = 0;
        for (OpenPurchaseOrderRowDto r : rows) {
            orders.add(r.orderUid());
            if (base.equals(r.currency())) {
                total = total.add(r.outstandingValue());
            } else {
                otherCcy++;
            }
        }

        return new OpenPurchaseOrdersDto(
                company.header(), asOf.toString(),
                branch != null ? branch.name() : null,
                supplier != null ? supplier.name() : null,
                base, rows,
                new OpenPurchaseOrdersTotalsDto(orders.size(), rows.size(), total, otherCcy),
                Instant.now().toString());
    }
}
