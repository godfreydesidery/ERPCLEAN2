package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.PurchasesBySupplierDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierRowDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierTotalsDto;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.BranchReadScope;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchases by Supplier — per supplier over a period: receipts, value received, returns and net
 * purchases; and, for a caller who may see supplier bills, what was billed and is still unpaid.
 *
 * <h2>Figures</h2>
 * <ul>
 *   <li><b>Received</b> — goods-receipt line value (excluding VAT) received in the period, LESS
 *       receipts voided in the period. Same signed treatment as the Goods Received Register, so the
 *       two reports' totals agree for the same period and branch.</li>
 *   <li><b>Returns</b> — CONFIRMED purchase returns in the period, at the receipt cost they reverse
 *       (the same basis as Received), dated by confirmation. Shown only with
 *       {@code PURCHASE.RETURN.VIEW} — the gate of the returns screen itself.</li>
 *   <li><b>Billed / Unpaid</b> — supplier bills (not drafts, not opening balances) DATED in the
 *       period: their net amount, and what is still outstanding on those same bills today. Shown
 *       only with {@code AP.VIEW}. Billing is a separate event from receiving, so Billed is not
 *       expected to equal Received for any one period.</li>
 * </ul>
 * A column the caller may not see is null on every row and in the totals — never zero.
 *
 * <p>Rows are keyed by (supplier, currency); totals sum the company's base currency only and count
 * foreign-currency rows in {@code rowsInOtherCurrency}.
 */
@Component
@Transactional(readOnly = true)
public class PurchasesBySupplierQuery {

    private final JdbcTemplate          jdbc;
    private final ScopeGuard            scopeGuard;
    private final BranchReadGuard       branchGuard;
    private final PurchaseReportSupport support;

    public PurchasesBySupplierQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
            BranchReadGuard branchGuard, PurchaseReportSupport support) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.support     = support;
    }

    /**
     * @param showReturns whether the caller may see purchase returns (decided by the caller's
     *                    permissions at the controller)
     * @param showBills   whether the caller may see supplier bills
     */
    public PurchasesBySupplierDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
            String branchUid, boolean showReturns, boolean showBills) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        PurchaseReportSupport.requirePeriod(fromDate, toDate);

        PurchaseReportSupport.Company company = support.loadCompany(companyId);
        OffsetDateTime from = company.startOf(fromDate);
        OffsetDateTime to   = company.endOf(toDate);

        PurchaseReportSupport.Ref branch =
                support.resolve("branches", "name", branchUid, companyId, "Branch");
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branch != null ? branch.id() : null);
        Long branchId = branch != null ? branch.id() : null;

        Map<String, Acc> bySupplier = new LinkedHashMap<>();
        loadReceipts(bySupplier, companyId, from, to, branchId, scope);
        if (showReturns) {
            loadReturns(bySupplier, companyId, from, to, branchId, scope);
        }
        if (showBills) {
            loadBills(bySupplier, companyId, fromDate, toDate, branchId, scope);
        }

        String base = company.baseCurrency();
        List<Acc> ordered = new ArrayList<>(bySupplier.values());
        ordered.sort(Comparator
                .comparing((Acc a) -> !base.equals(a.currency))   // base currency first
                .thenComparing(a -> a.received, Comparator.reverseOrder())
                .thenComparing(a -> a.name != null ? a.name : ""));

        List<PurchasesBySupplierRowDto> rows = new ArrayList<>(ordered.size());
        long tReceipts = 0;
        BigDecimal tReceived = BigDecimal.ZERO;
        BigDecimal tReturns  = BigDecimal.ZERO;
        BigDecimal tBilled   = BigDecimal.ZERO;
        BigDecimal tUnpaid   = BigDecimal.ZERO;
        long otherCcy = 0;
        for (Acc a : ordered) {
            BigDecimal returns = showReturns ? a.returns : null;
            BigDecimal net     = showReturns ? a.received.subtract(a.returns) : null;
            rows.add(new PurchasesBySupplierRowDto(
                    a.code, a.name, a.currency, a.receipts, a.received,
                    returns, net,
                    showBills ? a.billed : null,
                    showBills ? a.unpaid : null));
            if (base.equals(a.currency)) {
                tReceipts += a.receipts;
                tReceived = tReceived.add(a.received);
                tReturns  = tReturns.add(a.returns);
                tBilled   = tBilled.add(a.billed);
                tUnpaid   = tUnpaid.add(a.unpaid);
            } else {
                otherCcy++;
            }
        }
        PurchasesBySupplierTotalsDto totals = new PurchasesBySupplierTotalsDto(
                tReceipts, tReceived,
                showReturns ? tReturns : null,
                showReturns ? tReceived.subtract(tReturns) : null,
                showBills ? tBilled : null,
                showBills ? tUnpaid : null,
                otherCcy);

        return new PurchasesBySupplierDto(
                company.header(), fromDate.toString(), toDate.toString(),
                branch != null ? branch.name() : null,
                base, showReturns, showBills, rows, totals, Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /** Running figures for one (supplier, currency). */
    private static final class Acc {
        final String code;
        final String name;
        final String currency;
        long       receipts;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal returns  = BigDecimal.ZERO;
        BigDecimal billed   = BigDecimal.ZERO;
        BigDecimal unpaid   = BigDecimal.ZERO;

        Acc(String code, String name, String currency) {
            this.code = code;
            this.name = name;
            this.currency = currency;
        }
    }

    private static Acc acc(Map<String, Acc> map, long supplierId, String code, String name,
                           String currency) {
        return map.computeIfAbsent(supplierId + "|" + currency, k -> new Acc(code, name, currency));
    }

    private void loadReceipts(Map<String, Acc> map, Long companyId, OffsetDateTime from,
                              OffsetDateTime to, Long branchId, BranchReadScope scope) {
        String branchSql = branchId != null ? " AND gr.branch_id = ?" : scope.sql("gr.branch_id");
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        if (branchId != null) params.add(branchId);
        params.add(companyId);
        params.add(from);
        params.add(to);
        if (branchId != null) params.add(branchId);

        String sql = """
                WITH e AS (
                    SELECT gr.id AS gr_id, gr.supplier_id, grl.currency,
                           'RECEIPT' AS entry_type, grl.line_cost_amount AS value
                    FROM goods_receipt_lines grl
                    JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
                    WHERE gr.company_id = ?
                      AND gr.status IN ('RECEIVED','VOID')
                      AND gr.received_at >= ? AND gr.received_at < ?""" + branchSql + """

                    UNION ALL
                    SELECT gr.id, gr.supplier_id, grl.currency,
                           'VOID', -grl.line_cost_amount
                    FROM goods_receipt_lines grl
                    JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
                    WHERE gr.company_id = ?
                      AND gr.status = 'VOID'
                      AND gr.voided_at >= ? AND gr.voided_at < ?""" + branchSql + """

                )
                SELECT e.supplier_id, s.code AS supplier_code, s.display_name AS supplier_name,
                       e.currency,
                       COUNT(DISTINCT e.gr_id) FILTER (WHERE e.entry_type = 'RECEIPT') AS receipts,
                       COALESCE(SUM(e.value), 0)                                       AS value
                FROM e
                LEFT JOIN suppliers s ON s.id = e.supplier_id
                GROUP BY e.supplier_id, s.code, s.display_name, e.currency
                """;
        jdbc.query(sql, rs -> {
            Acc a = acc(map, rs.getLong("supplier_id"), rs.getString("supplier_code"),
                    rs.getString("supplier_name"), rs.getString("currency"));
            a.receipts += rs.getLong("receipts");
            a.received  = a.received.add(rs.getBigDecimal("value"));
        }, params.toArray());
    }

    private void loadReturns(Map<String, Acc> map, Long companyId, OffsetDateTime from,
                             OffsetDateTime to, Long branchId, BranchReadScope scope) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        if (branchId != null) params.add(branchId);
        String sql = """
                SELECT pr.supplier_id, s.code AS supplier_code, s.display_name AS supplier_name,
                       prl.currency, COALESCE(SUM(prl.line_value_amount), 0) AS value
                FROM purchase_return_lines prl
                JOIN purchase_returns pr ON pr.id = prl.purchase_return_id
                LEFT JOIN suppliers s ON s.id = pr.supplier_id
                WHERE pr.company_id = ?
                  AND pr.status = 'CONFIRMED'
                  AND pr.confirmed_at >= ? AND pr.confirmed_at < ?"""
                + (branchId != null ? " AND pr.branch_id = ?" : scope.sql("pr.branch_id")) + """

                GROUP BY pr.supplier_id, s.code, s.display_name, prl.currency
                """;
        jdbc.query(sql, rs -> {
            Acc a = acc(map, rs.getLong("supplier_id"), rs.getString("supplier_code"),
                    rs.getString("supplier_name"), rs.getString("currency"));
            a.returns = a.returns.add(rs.getBigDecimal("value"));
        }, params.toArray());
    }

    /**
     * Bills dated in the period. bill_date is a DATE, so the period is compared as dates — no time
     * zone arithmetic. A branch filter keeps only bills recorded against that branch: a bill
     * entered with no branch belongs to no branch's figures.
     */
    private void loadBills(Map<String, Acc> map, Long companyId, LocalDate fromDate,
                           LocalDate toDate, Long branchId, BranchReadScope scope) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(fromDate);
        params.add(toDate);
        if (branchId != null) params.add(branchId);
        String sql = """
                SELECT sb.supplier_id, s.code AS supplier_code, s.display_name AS supplier_name,
                       sb.currency,
                       COALESCE(SUM(sb.net_amount), 0)         AS billed,
                       COALESCE(SUM(sb.outstanding_amount), 0) AS unpaid
                FROM supplier_bills sb
                LEFT JOIN suppliers s ON s.id = sb.supplier_id
                WHERE sb.company_id = ?
                  AND sb.status <> 'DRAFT'
                  AND sb.source = 'BILL'
                  AND sb.bill_date >= ? AND sb.bill_date <= ?"""
                + (branchId != null ? " AND sb.branch_id = ?" : scope.sql("sb.branch_id")) + """

                GROUP BY sb.supplier_id, s.code, s.display_name, sb.currency
                """;
        jdbc.query(sql, rs -> {
            Acc a = acc(map, rs.getLong("supplier_id"), rs.getString("supplier_code"),
                    rs.getString("supplier_name"), rs.getString("currency"));
            a.billed = a.billed.add(rs.getBigDecimal("billed"));
            a.unpaid = a.unpaid.add(rs.getBigDecimal("unpaid"));
        }, params.toArray());
    }
}
