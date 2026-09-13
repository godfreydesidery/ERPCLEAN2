package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.ProfitabilityReportDto;
import com.erp.modules.sales.domain.dto.ProfitabilityRowDto;
import com.erp.modules.sales.domain.dto.ProfitabilityTotalsDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.erp.modules.sales.domain.dto.ProfitabilityDepartmentRowDto;
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
 * Profitability Report (K-2026-08-30 #2) — gross sales, VAT, net, cost of sales and profit, per
 * product and in total, over a date range.
 *
 * <p><b>Where the figures come from.</b> Finalised sales invoices give gross, VAT and net directly
 * ({@code sales_invoice_lines} carries all three, so net is read rather than re-derived and cannot
 * disagree with the invoice the customer holds). Cost of sales is the value of the
 * {@code SALE_ISSUE} stock movement posted for the same invoice — the cost at the moment of sale,
 * not today's average, which is what makes the profit reproducible months later.
 *
 * <p><b>Why two queries rather than one join.</b> Exactly the reason {@link SalesReportQuery}
 * documents: {@code StockPostingService}'s idempotency key is (source_event_uid, product_id), so at
 * most one SALE_ISSUE row exists per (invoice, product), while an invoice may carry TWO lines for
 * one product (a price-override split). Joining the movement onto both lines in one grouped query
 * would fan its value across them and silently double-count cost of sales — on a profit report, the
 * one number nobody would catch by eye.
 *
 * <p><b>Unknown cost is reported as unknown.</b> A SALE_ISSUE with a null {@code value_amount} means
 * that stock was sold before any cost was ever established for it. Treating that as zero does not
 * make the profit conservative — it reports the entire sale as profit. Such a product's cost AND
 * profit are both null here, and the totals count them, exactly as the ratified honest-margin rule
 * on the Sales Report requires. A profit report that quietly overstates profit is worse than none.
 *
 * <p>Cross-module reads (products, branches, companies) are scalar native-SQL joins — no entity or
 * service crosses the module boundary.
 */
@Component
@Transactional(readOnly = true)
public class ProfitabilityReportQuery {

    /** Percentage scale on the department view, matching the client's own report (two decimals). */
    private static final int PCT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public ProfitabilityReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    /**
     * @param branchUid optional; null covers every branch in the company
     */
    public ProfitabilityReportDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
                                          String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }

        CompanyHeader header = loadCompanyHeader(companyId);
        ZoneId zone = ZoneId.of(header.timeZone() != null ? header.timeZone() : DEFAULT_TIME_ZONE);

        // finalised_at is timestamptz; bind OffsetDateTime, not a raw Instant — the PG JDBC driver
        // cannot infer the SQL type for java.time.Instant via JdbcTemplate.
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to   = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef branch = resolveNamedRef("branches", "name", branchUid, companyId, "Branch");

        String filterSql = "";
        List<Object> filterParams = new ArrayList<>();
        if (branch != null) {
            filterSql = " AND i.branch_id = ?";
            filterParams.add(branch.id());
        }

        Views views = queryRows(companyId, from, to, filterSql, filterParams);
        List<ProfitabilityRowDto> rows = views.products();

        ReportCompanyHeaderDto companyDto = new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());

        return new ProfitabilityReportDto(
                companyDto,
                fromDate.toString(),
                toDate.toString(),
                branch != null ? branch.name() : null,
                header.baseCurrency(),
                rows,
                views.departments(),
                totalsOf(rows),
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /** Both views of the same figures, built from one pass over the data. */
    record Views(List<ProfitabilityRowDto> products,
                 List<ProfitabilityDepartmentRowDto> departments) {}

    private Views queryRows(Long companyId, OffsetDateTime from,
                            OffsetDateTime to, String filterSql,
                            List<Object> filterParams) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        params.addAll(filterParams);

        String sql = """
                SELECT l.product_id                AS product_id,
                       l.product_code              AS product_code,
                       l.product_name              AS product_name,
                       COALESCE(NULLIF(TRIM(p.category), ''), '(no department)') AS department,
                       SUM(l.quantity)             AS qty_sold,
                       SUM(l.gross_amount)         AS gross_sales,
                       SUM(l.vat_amount)           AS vat_amount,
                       SUM(l.net_amount)           AS net_amount,
                       SUM(COALESCE(l.line_discount_amount, 0)) AS discount,
                       -- The sale, split by how it is taxed. Summed over net (VAT-exclusive)
                       -- amounts so the three add back to net_amount exactly, which is the
                       -- identity the client's own report reconciles on.
                       SUM(CASE WHEN l.vat_status = 'STANDARD'   THEN l.net_amount ELSE 0 END)
                                                   AS vat_portion,
                       SUM(CASE WHEN l.vat_status = 'EXEMPT'     THEN l.net_amount ELSE 0 END)
                                                   AS exempt_portion,
                       SUM(CASE WHEN l.vat_status = 'ZERO_RATED' THEN l.net_amount ELSE 0 END)
                                                   AS zero_rated_portion
                FROM sales_invoice_lines l
                JOIN sales_invoices i ON i.id = l.invoice_id
                LEFT JOIN products p ON p.id = l.product_id AND p.company_id = i.company_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """ + filterSql + """

                GROUP BY l.product_id, l.product_code, l.product_name, p.category
                ORDER BY l.product_code NULLS LAST
                """;

        List<Object[]> raw = jdbc.query(sql,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("product_id"),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("qty_sold"),
                        rs.getBigDecimal("gross_sales"),
                        rs.getBigDecimal("vat_amount"),
                        rs.getBigDecimal("net_amount"),
                        rs.getString("department"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("vat_portion"),
                        rs.getBigDecimal("exempt_portion"),
                        rs.getBigDecimal("zero_rated_portion")
                },
                params.toArray());

        Map<Long, Cogs> cogsByProduct = queryCogsByProduct(companyId, from, to, filterSql, filterParams);

        List<ProfitabilityRowDto> rows = new ArrayList<>(raw.size());
        Map<String, DeptAcc> dept = new java.util.LinkedHashMap<>();
        for (Object[] r : raw) {
            Long       productId = (Long) r[0];
            BigDecimal netAmount = zeroIfNull((BigDecimal) r[6]);

            Cogs cogsRow = cogsByProduct.get(productId);
            // Some of this product's stock left the shelf before it had ever been costed. The cost
            // we can see is an understatement of unknown size, so neither it nor a profit derived
            // from it is reportable — see the class javadoc.
            boolean costUnknown = cogsRow != null && cogsRow.unvaluedMovements() > 0;

            BigDecimal costOfSales = costUnknown
                    ? null
                    : (cogsRow != null ? zeroIfNull(cogsRow.value()) : BigDecimal.ZERO);
            BigDecimal profit = costOfSales != null ? netAmount.subtract(costOfSales) : null;

            rows.add(new ProfitabilityRowDto(
                    (String) r[1],
                    (String) r[2],
                    zeroIfNull((BigDecimal) r[3]),
                    zeroIfNull((BigDecimal) r[4]),
                    zeroIfNull((BigDecimal) r[5]),
                    netAmount,
                    costOfSales,
                    profit));

            dept.computeIfAbsent((String) r[7], DeptAcc::new).add(r, costOfSales);
        }
        return new Views(rows, dept.values().stream().map(DeptAcc::toRow).toList());
    }

    /**
     * Accumulates one department. Kept as a mutable accumulator rather than a stream collector
     * because an unknown cost has to poison the department's cost and every figure drawn from it,
     * and that is a rule, not a sum.
     *
     * <p>Package-private so the identities the client reconciles on can be pinned against their own
     * figures without a database standing in the way.
     */
    static final class DeptAcc {
        private final String name;
        private BigDecimal gross = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal vat = BigDecimal.ZERO;
        private BigDecimal net = BigDecimal.ZERO;
        private BigDecimal vatPortion = BigDecimal.ZERO;
        private BigDecimal exemptPortion = BigDecimal.ZERO;
        private BigDecimal zeroPortion = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private int unknownCost = 0;

        DeptAcc(String name) {
            this.name = name;
        }

        void add(Object[] r, BigDecimal costOfSales) {
            gross         = gross.add(zeroIfNull((BigDecimal) r[4]));
            vat           = vat.add(zeroIfNull((BigDecimal) r[5]));
            net           = net.add(zeroIfNull((BigDecimal) r[6]));
            discount      = discount.add(zeroIfNull((BigDecimal) r[8]));
            vatPortion    = vatPortion.add(zeroIfNull((BigDecimal) r[9]));
            exemptPortion = exemptPortion.add(zeroIfNull((BigDecimal) r[10]));
            zeroPortion   = zeroPortion.add(zeroIfNull((BigDecimal) r[11]));
            if (costOfSales == null) {
                unknownCost++;
            } else {
                cost = cost.add(costOfSales);
            }
        }

        ProfitabilityDepartmentRowDto toRow() {
            // One uncosted product makes the whole department's cost an understatement of unknown
            // size. Reporting a contribution drawn from it would overstate profit, which is the
            // exact failure the honest-margin fix existed to end.
            BigDecimal costOut = unknownCost > 0 ? null : cost;
            BigDecimal contribution = costOut != null ? net.subtract(costOut) : null;
            BigDecimal margin = contribution != null && net.signum() != 0
                    ? contribution.multiply(HUNDRED).divide(net, PCT_SCALE, RoundingMode.HALF_UP)
                    : null;
            // Markup on a zero cost is unanswerable, not infinite.
            BigDecimal markup = contribution != null && costOut != null && costOut.signum() != 0
                    ? contribution.multiply(HUNDRED).divide(costOut, PCT_SCALE, RoundingMode.HALF_UP)
                    : null;
            return new ProfitabilityDepartmentRowDto(
                    name, gross, discount, gross.subtract(discount), net, vat,
                    vatPortion, exemptPortion, zeroPortion,
                    costOut, contribution, margin, markup, unknownCost);
        }
    }

    /**
     * Cost of sale for one product, plus how much of it could not be costed at all.
     *
     * @param unvaluedMovements SALE_ISSUE rows carrying no {@code value_amount} — stock sold before
     *                          any cost was established for it
     */
    private record Cogs(BigDecimal value, long unvaluedMovements) {}

    private Map<Long, Cogs> queryCogsByProduct(Long companyId, OffsetDateTime from, OffsetDateTime to,
                                                String filterSql, List<Object> filterParams) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(from);
        params.add(to);
        params.addAll(filterParams);

        String sql = """
                SELECT sm.product_id AS product_id,
                       COALESCE(SUM(ABS(sm.value_amount)), 0) AS cogs,
                       COUNT(*) FILTER (WHERE sm.value_amount IS NULL) AS unvalued
                FROM stock_movements sm
                JOIN sales_invoices i ON i.uid = sm.source_document_uid
                WHERE sm.company_id = ?
                  AND sm.movement_type = 'SALE_ISSUE'
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """ + filterSql + """

                GROUP BY sm.product_id
                """;

        Map<Long, Cogs> result = new HashMap<>();
        // Block body, not an expression: Map.put RETURNS a value, which makes the lambda match
        // ResultSetExtractor as well as RowCallbackHandler and the overload ambiguous.
        jdbc.query(sql,
                rs -> {
                    result.put(rs.getLong("product_id"),
                            new Cogs(rs.getBigDecimal("cogs"), rs.getLong("unvalued")));
                },
                params.toArray());
        return result;
    }

    /**
     * Sums what is known and counts what is not.
     *
     * <p>Package-private and static so the arithmetic — specifically, that an unknown cost is
     * EXCLUDED from the totals rather than added in as zero — can be exercised without a database.
     */
    static ProfitabilityTotalsDto totalsOf(List<ProfitabilityRowDto> rows) {
        BigDecimal qty    = BigDecimal.ZERO;
        BigDecimal gross  = BigDecimal.ZERO;
        BigDecimal vat    = BigDecimal.ZERO;
        BigDecimal net    = BigDecimal.ZERO;
        BigDecimal cost   = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        int unknownCost   = 0;

        for (ProfitabilityRowDto r : rows) {
            qty   = qty.add(zeroIfNull(r.qtySold()));
            gross = gross.add(zeroIfNull(r.grossSales()));
            vat   = vat.add(zeroIfNull(r.vatAmount()));
            net   = net.add(zeroIfNull(r.netAmount()));
            if (r.costOfSales() != null && r.profit() != null) {
                cost   = cost.add(r.costOfSales());
                profit = profit.add(r.profit());
            } else {
                unknownCost++;
            }
        }
        return new ProfitabilityTotalsDto(qty, gross, vat, net, cost, profit, unknownCost);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

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

    // -------------------------------------------------------------------------

    private record NamedRef(Long id, String name) {}

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String timeZone, String baseCurrency) {}
}
