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
        return jdbc.query(sql, (rs, n) -> new Day(rs.getString("day"), rs.getLong("invoices"),
                        rs.getBigDecimal("gross")),
                zone.getId(), companyId, branchId, from, to);
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
        return jdbc.query(sql, (rs, n) -> new Tender(rs.getString("tender"),
                        rs.getLong("payments"), rs.getBigDecimal("amount")),
                companyId, branchId, from, to);
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
