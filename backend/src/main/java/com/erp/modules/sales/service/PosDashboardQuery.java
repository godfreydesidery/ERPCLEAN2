package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosDashboardDto;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Day;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Department;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Item;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Tender;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The till's sales dashboard (Tulia 2026-11-02) — see {@link PosDashboardDto}.
 *
 * <p>Same rules as the Sales Report and the Payment Summary: only FINALISED invoices count,
 * windowed on {@code finalised_at} in the company's time zone; line money is converted to the base
 * currency at the rate stamped on each invoice ({@link BaseCurrencySql}); tenders are netted of
 * change. Scoped to the caller's ACTIVE branch — the till's own shop — which the request filter
 * has already verified the caller is assigned to.
 */
@Component
@Transactional(readOnly = true)
public class PosDashboardQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    private static final String NO_DEPARTMENT = "(no department)";
    /** A year and a day: enough for any dashboard, and bounds the work one request can ask for. */
    private static final long MAX_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final ScopeGuard scopeGuard;

    public PosDashboardQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc = jdbc;
        this.scopeGuard = scopeGuard;
    }

    public PosDashboardDto report(LocalDate fromDate, LocalDate toDate) {
        RequestContext.Principal principal = RequestContext.get();
        Long companyId = principal.companyId();
        scopeGuard.assertCanActIn(principal, companyId);
        Long branchId = principal.branchId();
        if (branchId == null) {
            throw new IllegalStateException("Choose a branch first.");
        }
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_DAYS) {
            throw new IllegalArgumentException("Choose a range of one year or less.");
        }

        Map<String, Object> company = jdbc.queryForMap(
                "SELECT time_zone, base_currency FROM companies WHERE id = ?", companyId);
        String tz = (String) company.get("time_zone");
        ZoneId zone = ZoneId.of(tz != null ? tz : DEFAULT_TIME_ZONE);
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        String branchName = jdbc.queryForObject(
                "SELECT name FROM branches WHERE id = ? AND company_id = ?",
                String.class, branchId, companyId);
        int scale = BaseCurrencySql.baseScale(jdbc, companyId);

        List<Day> days = days(zone, companyId, branchId, from, to, scale);
        BigDecimal gross = BigDecimal.ZERO;
        long invoices = 0;
        for (Day d : days) {
            gross = gross.add(d.gross());
            invoices += d.invoices();
        }
        return new PosDashboardDto(fromDate.toString(), toDate.toString(), branchName,
                (String) company.get("base_currency"), gross, invoices, days,
                tenders(companyId, branchId, from, to, scale),
                departments(companyId, branchId, from, to, scale),
                Instant.now().toString());
    }

    private static final String WINDOW = """
            i.company_id = ? AND i.branch_id = ?
              AND i.status = 'FINALISED'
              AND i.finalised_at >= ? AND i.finalised_at < ?
            """;

    private List<Day> days(ZoneId zone, Long companyId, Long branchId, OffsetDateTime from,
                           OffsetDateTime to, int scale) {
        String sql = "SELECT to_char(i.finalised_at AT TIME ZONE ?, 'YYYY-MM-DD') AS day,\n"
                + "       COUNT(DISTINCT i.id) AS invoices,\n"
                + "       COALESCE(SUM(" + BaseCurrencySql.grossToBase("l.net_amount",
                        "l.vat_amount", "i.fx_rate", scale) + "), 0) AS gross\n"
                + "FROM sales_invoices i\n"
                + "LEFT JOIN sales_invoice_lines l ON l.invoice_id = i.id\n"
                + "WHERE " + WINDOW
                + "GROUP BY 1 ORDER BY 1";
        List<Day> sold = jdbc.query(sql, (rs, n) -> new Day(rs.getString("day"),
                        rs.getLong("invoices"), rs.getBigDecimal("gross")),
                zone.getId(), companyId, branchId, from, to);

        // ADR-0072: POS item returns come off the day they were refunded. The original sale stays
        // FINALISED above, so without this every refunded item would still read as sold.
        String refundSql = "SELECT to_char(r.created_at AT TIME ZONE ?, 'YYYY-MM-DD') AS day,\n"
                + "       SUM(" + BaseCurrencySql.grossToBase("r.net_amount", "r.vat_amount",
                        "i.fx_rate", scale) + ") AS refunded\n"
                + "FROM pos_sale_returns r\n"
                + "JOIN sales_invoices i ON i.id = r.sales_invoice_id\n"
                + "WHERE r.company_id = ? AND r.branch_id = ?\n"
                + "  AND r.created_at >= ? AND r.created_at < ?\n"
                + "GROUP BY 1";
        Map<String, BigDecimal> refunded = new LinkedHashMap<>();
        jdbc.query(refundSql, rs -> {
            refunded.put(rs.getString("day"), rs.getBigDecimal("refunded"));
        }, zone.getId(), companyId, branchId, from, to);
        if (refunded.isEmpty()) {
            return sold;
        }
        Map<String, Day> byDay = new java.util.TreeMap<>();
        for (Day d : sold) {
            byDay.put(d.date(), d);
        }
        refunded.forEach((day, amount) -> byDay.merge(day, new Day(day, 0, amount.negate()),
                (a, b) -> new Day(a.date(), a.invoices(), a.gross().subtract(amount))));
        return new ArrayList<>(byDay.values());
    }

    private List<Tender> tenders(Long companyId, Long branchId, OffsetDateTime from,
                                 OffsetDateTime to, int scale) {
        String sql = "SELECT p.tender_type AS tender, COUNT(*) AS payments,\n"
                + "       SUM(" + BaseCurrencySql.toBase("(p.amount - COALESCE(p.change_amount, 0))",
                        "i.fx_rate", scale) + ") AS amount\n"
                + "FROM sales_invoice_payments p\n"
                + "JOIN sales_invoices i ON i.id = p.invoice_id\n"
                + "WHERE " + WINDOW
                + "GROUP BY 1 ORDER BY 3 DESC, 1";
        List<Tender> taken = jdbc.query(sql, (rs, n) -> new Tender(rs.getString("tender"),
                        rs.getLong("payments"), rs.getBigDecimal("amount")),
                companyId, branchId, from, to);

        // ADR-0072: an item return is always refunded in CASH, so it comes off the CASH line.
        BigDecimal refunded = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + BaseCurrencySql.toBase("r.gross_amount", "i.fx_rate", scale)
                        + "), 0)\n"
                        + "FROM pos_sale_returns r JOIN sales_invoices i ON i.id = r.sales_invoice_id\n"
                        + "WHERE r.company_id = ? AND r.branch_id = ?\n"
                        + "  AND r.created_at >= ? AND r.created_at < ?",
                BigDecimal.class, companyId, branchId, from, to);
        if (refunded == null || refunded.signum() == 0) {
            return taken;
        }
        List<Tender> out = new ArrayList<>();
        boolean cashSeen = false;
        for (Tender t : taken) {
            if ("CASH".equals(t.tenderType())) {
                cashSeen = true;
                out.add(new Tender(t.tenderType(), t.payments(), t.amount().subtract(refunded)));
            } else {
                out.add(t);
            }
        }
        if (!cashSeen) {
            out.add(new Tender("CASH", 0, refunded.negate()));
        }
        return out;
    }

    private List<Department> departments(Long companyId, Long branchId, OffsetDateTime from,
                                         OffsetDateTime to, int scale) {
        // Grouped by product id and labelled with its current name, as the Sales Report does, so a
        // product renamed in the window is still one row.
        String sql = "SELECT COALESCE(NULLIF(TRIM(p.category), ''), '" + NO_DEPARTMENT
                + "') AS department,\n"
                + "       COALESCE(p.code, MAX(l.product_code)) AS code,\n"
                + "       COALESCE(p.name, MAX(l.product_name)) AS name,\n"
                + "       COALESCE(NULLIF(TRIM(u.symbol), ''), u.code) AS unit,\n"
                + "       SUM(l.qty_in_base) AS quantity,\n"
                + "       SUM(" + BaseCurrencySql.grossToBase("l.net_amount", "l.vat_amount",
                        "i.fx_rate", scale) + ") AS gross\n"
                + "FROM sales_invoice_lines l\n"
                + "JOIN sales_invoices i ON i.id = l.invoice_id\n"
                + "LEFT JOIN products p ON p.id = l.product_id\n"
                + "LEFT JOIN units_of_measure u ON u.id = p.base_unit_id\n"
                + "WHERE " + WINDOW
                + "GROUP BY 1, l.product_id, p.code, p.name, u.symbol, u.code\n"
                + "ORDER BY 6 DESC, 3";
        // Departments are merged case-insensitively ("Drinks" and "drinks" are one).
        Map<String, List<Item>> items = new LinkedHashMap<>();
        Map<String, String> label = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String dept = rs.getString("department");
            String key = dept.toLowerCase(java.util.Locale.ROOT);
            label.putIfAbsent(key, dept);
            items.computeIfAbsent(key, k -> new ArrayList<>()).add(new Item(
                    rs.getString("code"), rs.getString("name"), rs.getString("unit"),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("gross")));
        }, companyId, branchId, from, to);

        // ADR-0072: the quantities and money of POS item returns refunded in the window come off
        // the product they belong to (same department grouping and product labelling as above).
        String returnsSql = "SELECT COALESCE(NULLIF(TRIM(p.category), ''), '" + NO_DEPARTMENT
                + "') AS department,\n"
                + "       COALESCE(p.code, MAX(sl.product_code)) AS code,\n"
                + "       COALESCE(p.name, MAX(sl.product_name)) AS name,\n"
                + "       COALESCE(NULLIF(TRIM(u.symbol), ''), u.code) AS unit,\n"
                + "       SUM(rl.qty_returned_base) AS quantity,\n"
                + "       SUM(" + BaseCurrencySql.grossToBase("rl.net_amount", "rl.vat_amount",
                        "i.fx_rate", scale) + ") AS gross\n"
                + "FROM pos_sale_return_lines rl\n"
                + "JOIN pos_sale_returns r ON r.id = rl.pos_sale_return_id\n"
                + "JOIN sales_invoices i ON i.id = r.sales_invoice_id\n"
                + "JOIN sales_invoice_lines sl ON sl.id = rl.sales_invoice_line_id\n"
                + "LEFT JOIN products p ON p.id = rl.product_id\n"
                + "LEFT JOIN units_of_measure u ON u.id = p.base_unit_id\n"
                + "WHERE r.company_id = ? AND r.branch_id = ?\n"
                + "  AND r.created_at >= ? AND r.created_at < ?\n"
                + "GROUP BY 1, rl.product_id, p.code, p.name, u.symbol, u.code";
        jdbc.query(returnsSql, rs -> {
            String dept = rs.getString("department");
            String key = dept.toLowerCase(java.util.Locale.ROOT);
            label.putIfAbsent(key, dept);
            List<Item> list = items.computeIfAbsent(key, k -> new ArrayList<>());
            Item back = new Item(rs.getString("code"), rs.getString("name"), rs.getString("unit"),
                    rs.getBigDecimal("quantity").negate(), rs.getBigDecimal("gross").negate());
            for (int n = 0; n < list.size(); n++) {
                Item have = list.get(n);
                if (java.util.Objects.equals(have.code(), back.code())
                        && java.util.Objects.equals(have.name(), back.name())
                        && java.util.Objects.equals(have.unit(), back.unit())) {
                    list.set(n, new Item(have.code(), have.name(), have.unit(),
                            have.quantity().add(back.quantity()), have.gross().add(back.gross())));
                    return;
                }
            }
            list.add(back);
        }, companyId, branchId, from, to);

        List<Department> out = new ArrayList<>();
        for (var e : items.entrySet()) {
            BigDecimal qty = BigDecimal.ZERO;
            BigDecimal gross = BigDecimal.ZERO;
            for (Item i : e.getValue()) {
                qty = qty.add(i.quantity());
                gross = gross.add(i.gross());
            }
            out.add(new Department(label.get(e.getKey()), qty, gross, List.copyOf(e.getValue())));
        }
        out.sort((a, b) -> b.gross().compareTo(a.gross()));
        return out;
    }
}
