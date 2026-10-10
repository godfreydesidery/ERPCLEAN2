package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.SalesSummaryReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.dto.SalesSummaryTotalsDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.BranchReadScope;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales Summary — finalised sales over a period grouped by customer, agent, route, branch, day or
 * cashier, with cost of sales and margin per group.
 *
 * <p><b>Same figures as the Sales Report and the Profitability Report.</b> Only FINALISED invoices
 * count (drafts are not sales, voided ones were undone), windowed on {@code finalised_at} in the
 * company's time zone. Gross, discount, VAT and net are read from {@code sales_invoice_lines}
 * exactly as those reports read them, and — like them — converted to the company's BASE currency
 * line by line at the rate stamped on each invoice at finalise ({@link BaseCurrencySql}), so a
 * foreign-currency sale is neither added at 1:1 nor margined against a base-currency cost. Sales returns and AR credit notes are NOT deducted — neither
 * of those reports deducts them either, and a summary that disagreed with the register it summarises
 * would be worse than one that shares its known limitation. Quantity is summed in BASE units
 * ({@code qty_in_base}) so a pack and a single are not added together as two of the same thing.
 *
 * <p><b>Cost of sales, without double counting.</b> Cost is the value of the {@code SALE_ISSUE}
 * movement posted for the invoice — the cost at the moment of sale. It is read in a SEPARATE query
 * joined to the invoice header only, never to its lines: at most one SALE_ISSUE exists per
 * (invoice, product) while an invoice may carry two lines for one product, so a join onto lines
 * would fan one movement's value across both and silently double the cost. Every grouping is an
 * invoice-level attribute, so the movement query groups on exactly the same key and the two are
 * merged in Java.
 *
 * <p><b>Unknown cost is unknown.</b> A SALE_ISSUE with no {@code value_amount} is stock sold before
 * any cost was established. A group containing one has a null cost, margin and margin %, and is
 * counted in the totals rather than quietly reported as all-profit.
 *
 * <p>Cross-module reads (customers, agents, routes, branches, users) are scalar native-SQL joins —
 * no entity or service crosses the module boundary.
 */
@Component
@Transactional(readOnly = true)
public class SalesSummaryReportQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    private static final int PCT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Map key for the group whose key is absent (no route on the invoice, creator not recorded). */
    private static final String ABSENT_KEY = "\u0000";

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;

    public SalesSummaryReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
                                   BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
    }

    /**
     * @param groupBy   what a row stands for; null means CUSTOMER
     * @param branchUid optional; null covers every branch in the company
     */
    public SalesSummaryReportDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
                                        SalesSummaryGroupBy groupBy, String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }
        SalesSummaryGroupBy by = groupBy != null ? groupBy : SalesSummaryGroupBy.CUSTOMER;

        CompanyHeader header = loadCompanyHeader(companyId);
        ZoneId zone = ZoneId.of(header.timeZone() != null ? header.timeZone() : DEFAULT_TIME_ZONE);
        // finalised_at is timestamptz: bind OffsetDateTime, never a raw Instant (pgjdbc cannot
        // infer the SQL type of an Instant through JdbcTemplate).
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to   = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef branch = resolveBranch(branchUid, companyId);
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branch != null ? branch.id() : null);

        Grouping g = Grouping.of(by);
        Map<String, Cogs> cogs = queryCogs(g, zone, companyId, from, to, branch, scope);
        List<SalesSummaryRowDto> rows = queryRows(g, by, zone, companyId, from, to, branch, scope, cogs);

        return new SalesSummaryReportDto(
                header.toDto(),
                fromDate.toString(),
                toDate.toString(),
                by,
                branch != null ? branch.name() : null,
                header.baseCurrency(),
                rows,
                totalsOf(rows),
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /**
     * How one grouping is read. Every expression is a fixed literal chosen by the enum — nothing a
     * caller types reaches the SQL text. The DAY key carries one bind placeholder (the time zone),
     * which is why both queries put the key FIRST in the select list and bind the zone first.
     */
    private record Grouping(String keyExpr, String labelExpr, String codeExpr, String join,
                            boolean bindsZone) {
        static Grouping of(SalesSummaryGroupBy by) {
            return switch (by) {
                case CUSTOMER -> new Grouping("c.uid", "c.display_name", "c.code",
                        " LEFT JOIN customers c ON c.id = i.customer_id", false);
                case AGENT -> new Grouping("a.uid", "a.display_name", "a.code",
                        " LEFT JOIN agents a ON a.id = i.agent_id", false);
                case ROUTE -> new Grouping("r.uid", "r.name", "r.code",
                        " LEFT JOIN routes r ON r.id = i.route_id", false);
                case BRANCH -> new Grouping("b.uid", "b.name", "b.code",
                        " LEFT JOIN branches b ON b.id = i.branch_id", false);
                case CASHIER -> new Grouping("u.uid", "u.display_name", "u.username",
                        " LEFT JOIN app_users u ON u.id = i.created_by", false);
                case DAY -> new Grouping(
                        "to_char(i.finalised_at AT TIME ZONE ?, 'YYYY-MM-DD')",
                        "CAST(NULL AS TEXT)", "CAST(NULL AS TEXT)", "", true);
            };
        }
    }

    private List<SalesSummaryRowDto> queryRows(Grouping g, SalesSummaryGroupBy by, ZoneId zone,
                                               Long companyId, OffsetDateTime from,
                                               OffsetDateTime to, NamedRef branch, BranchReadScope scope,
                                               Map<String, Cogs> cogs) {
        List<Object> params = new ArrayList<>();
        if (g.bindsZone()) {
            params.add(zone.getId());
        }
        params.add(companyId);
        params.add(from);
        params.add(to);
        String branchSql = scope.sql("i.branch_id");
        if (branch != null) {
            branchSql = " AND i.branch_id = ?";
            params.add(branch.id());
        }

        // Money is converted to BASE line by line at the rate stamped on each invoice
        // (BaseCurrencySql) — the report is headed in the base currency and its cost of sales is
        // base, so a USD invoice summed at face would be mislabelled AND margined against TZS cost.
        int baseScale = BaseCurrencySql.baseScale(jdbc, companyId);
        String rate = "i.fx_rate";
        String sql = "SELECT " + g.keyExpr() + " AS gkey, "
                + g.labelExpr() + " AS glabel, "
                + g.codeExpr() + " AS gcode, "
                + """
                       COUNT(DISTINCT i.id)                              AS invoices,
                       COALESCE(SUM(l.qty_in_base), 0)                   AS qty,
                """
                + "       COALESCE(SUM(" + BaseCurrencySql.grossToBase("l.net_amount", "l.vat_amount",
                        rate, baseScale) + "), 0) AS gross,\n"
                + "       COALESCE(SUM(" + BaseCurrencySql.toBase("COALESCE(l.line_discount_amount, 0)",
                        rate, baseScale) + "), 0) AS discount,\n"
                + "       COALESCE(SUM(" + BaseCurrencySql.toBase("l.vat_amount", rate, baseScale)
                + "), 0) AS vat,\n"
                + "       COALESCE(SUM(" + BaseCurrencySql.toBase("l.net_amount", rate, baseScale)
                + "), 0) AS net,\n"
                + "       COUNT(DISTINCT i.id) FILTER (WHERE " + rate + " <> 1) AS foreign_invoices\n"
                + """
                FROM sales_invoices i
                JOIN sales_invoice_lines l ON l.invoice_id = i.id
                """
                + g.join()
                + """

                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """
                + branchSql
                + """

                GROUP BY 1, 2, 3
                """;

        List<SalesSummaryRowDto> rows = jdbc.query(sql, (rs, rowNum) -> {
            String key = rs.getString("gkey");
            BigDecimal net = rs.getBigDecimal("net");
            Cogs c = cogs.get(key != null ? key : ABSENT_KEY);

            // No SALE_ISSUE at all (services, or invoices raised from a delivery that issued the
            // stock) contributes no cost, exactly as on the Sales Report. An issue with NO value is
            // different: that stock was never costed, so the group's cost is unknown.
            long unknown = c != null ? c.unvalued() : 0L;
            BigDecimal cost = unknown > 0 ? null : (c != null ? c.value() : BigDecimal.ZERO);
            BigDecimal margin = cost != null ? net.subtract(cost) : null;

            return new SalesSummaryRowDto(
                    key,
                    labelFor(by, key, rs.getString("glabel")),
                    rs.getString("gcode"),
                    rs.getLong("invoices"),
                    rs.getBigDecimal("qty"),
                    rs.getBigDecimal("gross"),
                    rs.getBigDecimal("discount"),
                    rs.getBigDecimal("vat"),
                    net,
                    cost,
                    margin,
                    percentOf(margin, net),
                    unknown,
                    rs.getLong("foreign_invoices"));
        }, params.toArray());

        Comparator<SalesSummaryRowDto> order = by == SalesSummaryGroupBy.DAY
                // Chronological for the daily summary — a day-by-day list read out of order is
                // a different report.
                ? Comparator.comparing(SalesSummaryRowDto::groupKey,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // Biggest first everywhere else: "who sold the most" is the question.
                : Comparator.comparing(SalesSummaryRowDto::grossAmount).reversed()
                        .thenComparing(SalesSummaryRowDto::groupLabel,
                                Comparator.nullsLast(Comparator.naturalOrder()));
        List<SalesSummaryRowDto> sorted = new ArrayList<>(rows);
        sorted.sort(order);
        return sorted;
    }

    /** Cost of sale for one group, and how many of its items could not be costed at all. */
    private record Cogs(BigDecimal value, long unvalued) {}

    private Map<String, Cogs> queryCogs(Grouping g, ZoneId zone, Long companyId,
                                        OffsetDateTime from, OffsetDateTime to, NamedRef branch,
                                        BranchReadScope scope) {
        List<Object> params = new ArrayList<>();
        if (g.bindsZone()) {
            params.add(zone.getId());
        }
        params.add(companyId);
        params.add(companyId);
        params.add(from);
        params.add(to);
        String branchSql = scope.sql("i.branch_id");
        if (branch != null) {
            branchSql = " AND i.branch_id = ?";
            params.add(branch.id());
        }

        String sql = "SELECT " + g.keyExpr() + " AS gkey, "
                + """
                       COALESCE(SUM(ABS(sm.value_amount)), 0)          AS cogs,
                       COUNT(*) FILTER (WHERE sm.value_amount IS NULL) AS unvalued
                FROM stock_movements sm
                JOIN sales_invoices i ON i.uid = sm.source_document_uid
                """
                + g.join()
                + """

                WHERE sm.company_id = ?
                  AND i.company_id  = ?
                  AND sm.movement_type = 'SALE_ISSUE'
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ?
                  AND i.finalised_at <  ?
                """
                + branchSql
                + """

                GROUP BY 1
                """;

        Map<String, Cogs> result = new HashMap<>();
        // Block body, not an expression: Map.put returns a value, which would make the lambda
        // ambiguous between RowCallbackHandler and ResultSetExtractor.
        jdbc.query(sql, rs -> {
            String key = rs.getString("gkey");
            result.put(key != null ? key : ABSENT_KEY,
                    new Cogs(rs.getBigDecimal("cogs"), rs.getLong("unvalued")));
        }, params.toArray());
        return result;
    }

    private static String labelFor(SalesSummaryGroupBy by, String key, String label) {
        if (by == SalesSummaryGroupBy.DAY) {
            return key;
        }
        if (key == null) {
            return switch (by) {
                case ROUTE -> "(no route)";
                case CASHIER -> "(not recorded)";
                default -> "(none)";
            };
        }
        return label;
    }

    static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, PCT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Sums what is known and counts what is not.
     *
     * <p>Package-private and static so the rule that an unknown cost is EXCLUDED from the cost and
     * margin totals — and its group's net excluded from the margin % base — is testable without a
     * database.
     */
    static SalesSummaryTotalsDto totalsOf(List<SalesSummaryRowDto> rows) {
        long invoices = 0;
        BigDecimal qty      = BigDecimal.ZERO;
        BigDecimal gross    = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal vat      = BigDecimal.ZERO;
        BigDecimal net      = BigDecimal.ZERO;
        BigDecimal cost     = BigDecimal.ZERO;
        BigDecimal margin   = BigDecimal.ZERO;
        BigDecimal knownNet = BigDecimal.ZERO;
        int groupsUnknown = 0;
        long itemsUnknown = 0;

        for (SalesSummaryRowDto r : rows) {
            invoices += r.invoiceCount();
            qty      = qty.add(zeroIfNull(r.qty()));
            gross    = gross.add(zeroIfNull(r.grossAmount()));
            discount = discount.add(zeroIfNull(r.discount()));
            vat      = vat.add(zeroIfNull(r.vatAmount()));
            net      = net.add(zeroIfNull(r.netAmount()));
            itemsUnknown += r.unknownCostItems();
            if (r.costOfSales() != null && r.margin() != null) {
                cost     = cost.add(r.costOfSales());
                margin   = margin.add(r.margin());
                knownNet = knownNet.add(zeroIfNull(r.netAmount()));
            } else {
                groupsUnknown++;
            }
        }
        return new SalesSummaryTotalsDto(invoices, qty, gross, discount, vat, net, cost, margin,
                percentOf(margin, knownNet), groupsUnknown, itemsUnknown);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private NamedRef resolveBranch(String uid, Long companyId) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of("Branch", uid);
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
                        new ReportCompanyHeaderDto(
                                rs.getString("name"),
                                rs.getString("legal_name"),
                                rs.getString("address_line1"),
                                rs.getString("address_line2"),
                                rs.getString("city"),
                                rs.getString("region"),
                                rs.getString("country"),
                                rs.getString("contact_phone"),
                                rs.getString("contact_email"),
                                rs.getString("tax_id"),
                                rs.getString("vrn")),
                        rs.getString("time_zone"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    private record NamedRef(Long id, String name) {}

    private record CompanyHeader(ReportCompanyHeaderDto toDto, String timeZone,
                                 String baseCurrency) {}
}
