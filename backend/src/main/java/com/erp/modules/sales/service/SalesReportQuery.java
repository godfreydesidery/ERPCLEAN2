package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.SalesReportDto;
import com.erp.modules.sales.domain.dto.SalesReportRowDto;
import com.erp.modules.sales.domain.dto.SalesReportTotalsDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-product Sales Register over a date range (SAM Electronix go-live).
 *
 * <p>{@code amount} is GROSS (VAT-inclusive) sales; {@code margin} = net sales less cost-of-sale
 * at time of sale, matched to the {@code SALE_ISSUE} stock movement posted for the same invoice
 * (ADR-0020 D-2). Enriches with product/agent/route/supplier/branch/company via native SQL joins —
 * scalar cross-module reads (same pattern as {@code StockValuationQuery}; ADR boundary rule: no
 * entity/service import across modules).
 *
 * <p>Cost-of-sale is computed with a SEPARATE query rather than joining {@code stock_movements}
 * directly onto {@code sales_invoice_lines} in the main aggregate: {@code StockPostingService}'s
 * idempotency key is {@code (source_event_uid, product_id)}, so at most one {@code SALE_ISSUE} row
 * exists per (invoice, product) — but an invoice CAN carry two lines for the same product (e.g. a
 * price-override split). Joining the single movement row onto both lines in one grouped query would
 * fan the movement's value out across every matching line, double-counting COGS silently. Two
 * queries merged in Java avoids that entirely.
 */
@Component
@Transactional(readOnly = true)
public class SalesReportQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public SalesReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    public SalesReportDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
                                  String agentUid, String routeUid,
                                  String supplierUid, String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        CompanyHeader header = loadCompanyHeader(companyId);
        ZoneId zone = ZoneId.of(header.timeZone() != null ? header.timeZone() : DEFAULT_TIME_ZONE);

        // finalised_at is timestamptz; bind OffsetDateTime, not a raw Instant — the PG JDBC driver
        // cannot infer the SQL type for java.time.Instant via JdbcTemplate ("Can't infer the SQL
        // type to use for an instance of java.time.Instant").
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to   = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef agent    = resolveNamedRef("agents",    "display_name", agentUid,    companyId, "Agent");
        NamedRef route    = resolveNamedRef("routes",    "name",         routeUid,    companyId, "Route");
        NamedRef supplier = resolveNamedRef("suppliers", "display_name", supplierUid, companyId, "Supplier");
        NamedRef branch   = resolveNamedRef("branches",  "name",         branchUid,   companyId, "Branch");

        StringBuilder filterSql = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();
        if (branch != null) {
            filterSql.append(" AND i.branch_id = ?");
            filterParams.add(branch.id());
        }
        if (agent != null) {
            filterSql.append(" AND i.agent_id = ?");
            filterParams.add(agent.id());
        }
        if (route != null) {
            filterSql.append(" AND i.route_id = ?");
            filterParams.add(route.id());
        }
        if (supplier != null) {
            filterSql.append(" AND p.preferred_supplier_id = ?");
            filterParams.add(supplier.id());
        }

        // The on-hand column follows the branch filter. It used to be company-wide regardless, so a
        // branch-filtered register showed that branch's sales beside the WHOLE company's stock —
        // read at a counter as a stock discrepancy. Only the branch narrows it: agent/route/supplier
        // filter the sales, not where the goods physically are.
        List<SalesReportRowDto> rows = queryRows(companyId, from, to, filterSql.toString(),
                filterParams, branch != null ? branch.id() : null);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;
        BigDecimal totalMargin = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (SalesReportRowDto r : rows) {
            totalQty      = totalQty.add(r.qtySold());
            totalDiscount = totalDiscount.add(r.discount());
            totalVat      = totalVat.add(r.vat());
            totalMargin   = totalMargin.add(r.margin());
            totalAmount   = totalAmount.add(r.amount());
        }
        SalesReportTotalsDto totals = new SalesReportTotalsDto(
                totalQty, totalDiscount, totalVat, totalMargin, totalAmount);

        ReportCompanyHeaderDto companyDto = new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());

        return new SalesReportDto(
                companyDto, fromDate.toString(), toDate.toString(),
                supplier != null ? supplier.name() : null,
                agent != null ? agent.name() : null,
                route != null ? route.name() : null,
                header.baseCurrency(),
                rows, totals, Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private List<SalesReportRowDto> queryRows(Long companyId, OffsetDateTime from, OffsetDateTime to,
                                               String filterSql, List<Object> filterParams,
                                               Long stockBranchId) {
        List<Object> mainParams = new ArrayList<>();
        mainParams.add(companyId); // stock_on_hand subquery
        // Bound twice by design: the predicate is "this branch, or every branch when none was
        // asked for", so the same id is compared and null-checked in one pass.
        mainParams.add(stockBranchId);
        mainParams.add(stockBranchId);
        mainParams.add(companyId); // i.company_id
        mainParams.add(from);
        mainParams.add(to);
        mainParams.addAll(filterParams);

        String mainSql = """
                SELECT l.product_id                              AS product_id,
                       l.product_code                             AS product_code,
                       l.product_name                             AS product_name,
                       SUM(l.quantity)                            AS qty_sold,
                       SUM(COALESCE(l.line_discount_amount, 0))   AS discount,
                       SUM(l.vat_amount)                          AS vat,
                       SUM(l.net_amount)                          AS net_sales,
                       SUM(l.gross_amount)                        AS amount,
                       COALESCE(soh.qty, 0)                       AS current_stock
                FROM sales_invoice_lines l
                JOIN sales_invoices i ON i.id = l.invoice_id
                LEFT JOIN products p ON p.id = l.product_id
                LEFT JOIN (
                    SELECT product_id, SUM(quantity) AS qty
                    FROM stock_on_hand
                    WHERE company_id = ?
                      AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                    GROUP BY product_id
                ) soh ON soh.product_id = l.product_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """ + filterSql + """

                GROUP BY l.product_id, l.product_code, l.product_name, soh.qty
                ORDER BY l.product_code NULLS LAST
                """;

        List<Object[]> mainRows = jdbc.query(mainSql,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("product_id"),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("qty_sold"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("vat"),
                        rs.getBigDecimal("net_sales"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("current_stock")
                },
                mainParams.toArray());

        Map<Long, BigDecimal> cogsByProduct = queryCogsByProduct(companyId, from, to, filterSql, filterParams);

        List<SalesReportRowDto> rows = new ArrayList<>(mainRows.size());
        for (Object[] r : mainRows) {
            Long productId          = (Long) r[0];
            BigDecimal netSales     = (BigDecimal) r[6];
            // A SALE_ISSUE movement can exist with a NULL value_amount (product unvalued at sale
            // time), so the map may hold a null against a present key — coalesce to ZERO to keep
            // margin = net (no fabricated cost) rather than NPE on subtract.
            BigDecimal cogs         = cogsByProduct.get(productId);
            if (cogs == null) cogs = BigDecimal.ZERO;
            BigDecimal margin       = netSales.subtract(cogs);

            rows.add(new SalesReportRowDto(
                    (String) r[1],
                    (String) r[2],
                    (BigDecimal) r[8],
                    (BigDecimal) r[3],
                    (BigDecimal) r[4],
                    (BigDecimal) r[5],
                    margin,
                    (BigDecimal) r[7]));
        }
        return rows;
    }

    /**
     * Cost-of-sale per product = SUM of the matching SALE_ISSUE movement value across every
     * invoice in the filtered window. Unmatched lines (unvalued/service/kit) contribute 0 — margin
     * then equals net for that portion (no fabricated cost, D-2 edge).
     */
    private Map<Long, BigDecimal> queryCogsByProduct(Long companyId, OffsetDateTime from, OffsetDateTime to,
                                                      String filterSql, List<Object> filterParams) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        params.addAll(filterParams);

        String sql = """
                SELECT sm.product_id AS product_id,
                       COALESCE(SUM(ABS(sm.value_amount)), 0) AS cogs
                FROM stock_movements sm
                JOIN sales_invoices i ON i.uid = sm.source_document_uid
                LEFT JOIN products p ON p.id = sm.product_id
                WHERE sm.company_id = ?
                  AND sm.movement_type = 'SALE_ISSUE'
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """ + filterSql + """

                GROUP BY sm.product_id
                """;

        Map<Long, BigDecimal> result = new HashMap<>();
        jdbc.query(sql,
                rs -> {
                    result.put(rs.getLong("product_id"), rs.getBigDecimal("cogs"));
                },
                params.toArray());
        return result;
    }

    private CompanyHeader loadCompanyHeader(Long companyId) {
        List<CompanyHeader> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new CompanyHeader(
                        rs.getString("name"),
                        rs.getString("legal_name"),
                        rs.getString("tax_id"),
                        rs.getString("vrn"),
                        rs.getString("contact_phone"),
                        rs.getString("contact_email"),
                        rs.getString("address_line1"),
                        rs.getString("address_line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("country"),
                        rs.getString("time_zone"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    /** Resolve an optional uid filter to (id, display name), scoped to the caller's company. */
    private NamedRef resolveNamedRef(String table, String nameColumn, String uid,
                                      Long companyId, String entityName) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        String sql = "SELECT id, " + nameColumn + " AS name FROM " + table
                + " WHERE uid = ? AND company_id = ?";
        List<NamedRef> found = jdbc.query(sql,
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of(entityName, uid);
        }
        return found.get(0);
    }

    // -------------------------------------------------------------------------

    private record NamedRef(Long id, String name) {}

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String timeZone, String baseCurrency) {}
}
