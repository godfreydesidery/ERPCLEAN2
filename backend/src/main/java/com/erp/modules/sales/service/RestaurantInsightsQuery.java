package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.RestaurantDashboardDto;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Comp;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Day;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Department;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Hour;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Item;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Kpis;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Live;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto.Weekday;
import com.erp.modules.sales.domain.dto.RestaurantDaypartDto;
import com.erp.modules.sales.domain.dto.RestaurantDaypartDto.Slot;
import com.erp.modules.sales.domain.dto.RestaurantPaymentsReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesRowDto;
import com.erp.modules.sales.service.RestaurantReportQuery.Filter;
import com.erp.modules.sales.service.RestaurantReportQuery.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The restaurant dashboard (also the Flash Report), sales by hour and day of the week, and payments
 * and order completion — Tulia/J COB 2026-11-03. Same scope rules as {@link RestaurantReportQuery}
 * (one branch, or every branch the caller may read), whose window and filters it reuses.
 *
 * <p>Sales are finalised receipts at gross in the base currency ({@link BaseCurrencySql}), counted
 * when paid; a voided receipt is counted apart, never in sales. Orders, guests and waiters come
 * from the restaurant orders paid in the range. Department and item figures are summed from the
 * receipt lines. Native SQL, the same pattern as the other report queries.
 */
@Component
@Transactional(readOnly = true)
public class RestaurantInsightsQuery {

    /** A year and a day: bounds the work one request can ask for. */
    private static final long MAX_DAYS = 366;
    private static final int TOP_ITEMS = 10;
    private static final String NO_DEPARTMENT = "(no department)";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcTemplate          jdbc;
    private final RestaurantReportQuery reports;

    public RestaurantInsightsQuery(JdbcTemplate jdbc, RestaurantReportQuery reports) {
        this.jdbc    = jdbc;
        this.reports = reports;
    }

    // ---------------------------------------------------------------- dashboard

    public RestaurantDashboardDto dashboard(Long companyId, LocalDate fromDate, LocalDate toDate,
                                            String branchUid) {
        Window w = window(companyId, fromDate, toDate, branchUid);
        Filter fo = reports.branchFilter(companyId, branchUid, "o.branch_id");
        Filter fp = reports.branchFilter(companyId, branchUid, "po.branch_id");
        long span = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        LocalDate prevFrom = fromDate.minusDays(span);
        LocalDate prevTo = fromDate.minusDays(1);
        ZoneId zone = w.zone();

        Kpis current = kpis(companyId, w, fo, fp, w.from(), w.to());
        Kpis previous = kpis(companyId, w, fo, fp,
                prevFrom.atStartOfDay(zone).toOffsetDateTime(),
                fromDate.atStartOfDay(zone).toOffsetDateTime());

        List<Department> departments = new ArrayList<>();
        List<Item> topItems = new ArrayList<>();
        departmentsAndItems(companyId, w, departments, topItems);

        WaiterSalesReportDto bySales =
                reports.waiterSales(companyId, fromDate, toDate, null, branchUid);
        List<WaiterSalesRowDto> waiters = new ArrayList<>(bySales.rows());
        // What was sold without a table order, so the waiters add up to the sales.
        long counterReceipts = current.receipts() - bySales.totals().bills();
        BigDecimal counterSales = current.sales().subtract(bySales.totals().amount());
        if (counterReceipts > 0 && counterSales.signum() > 0) {
            waiters.add(new WaiterSalesRowDto(null, "Counter sales (no table order)",
                    counterReceipts, 0, counterSales,
                    counterSales.divide(BigDecimal.valueOf(counterReceipts), 2,
                            RoundingMode.HALF_EVEN)));
        }

        return new RestaurantDashboardDto(w.header().dto(), fromDate.toString(), toDate.toString(),
                w.branchName(), w.header().baseCurrency(), current, previous, prevFrom.toString(),
                prevTo.toString(), live(companyId, fo), days(companyId, w, fromDate, toDate),
                hours(companyId, w), weekdays(companyId, w, fromDate, toDate), departments,
                topItems, waiters, tenders(companyId, w), complimentary(companyId, w),
                Instant.now().toString());
    }

    private Kpis kpis(Long companyId, Window w, Filter fo, Filter fp, OffsetDateTime from,
                      OffsetDateTime to) {
        int scale = w.baseScale();
        String gross = BaseCurrencySql.toBase("i.gross_total_amount", "i.fx_rate", scale);
        Map<String, Object> receipts = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE i.status = 'FINALISED') AS receipts,
                       COALESCE(SUM(""" + gross + """
                ) FILTER (WHERE i.status = 'FINALISED'), 0) AS sales,
                       COUNT(*) FILTER (WHERE i.status = 'VOID') AS voided
                FROM sales_invoices i
                WHERE i.company_id = ?
                  AND i.status IN ('FINALISED', 'VOID')
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql(), args(companyId, from, to, w.params()));
        BigDecimal sales = (BigDecimal) receipts.get("sales");
        long receiptCount = ((Number) receipts.get("receipts")).longValue();

        Map<String, Object> orders = jdbc.queryForMap("""
                SELECT COUNT(*) AS orders, COALESCE(SUM(o.guest_count), 0) AS guests,
                       COALESCE(SUM(CASE WHEN o.guest_count > 0 THEN """ + gross + """
                 END), 0) AS seated_sales
                FROM restaurant_orders o
                JOIN sales_invoices i ON i.id = o.sales_invoice_id
                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND i.status = 'FINALISED'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + fo.sql(), args(companyId, from, to, fo.params()));
        long guests = ((Number) orders.get("guests")).longValue();

        BigDecimal discounts = jdbc.queryForObject("""
                SELECT COALESCE(SUM(""" + BaseCurrencySql.lineDiscountToBase("i.fx_rate", scale)
                + """
                ), 0)
                FROM sales_invoice_lines l
                JOIN sales_invoices i ON i.id = l.invoice_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql(), BigDecimal.class, args(companyId, from, to, w.params()));

        BigDecimal complimentary = jdbc.queryForObject("""
                SELECT COALESCE(SUM(""" + BaseCurrencySql.toBase(
                        "(p.amount - COALESCE(p.change_amount, 0))", "i.fx_rate", scale) + """
                ), 0)
                FROM sales_invoice_payments p
                JOIN sales_invoices i ON i.id = p.invoice_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND p.tender_type = 'COMPLIMENTARY'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql(), BigDecimal.class, args(companyId, from, to, w.params()));

        BigDecimal refunds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(po.amount), 0)
                FROM pos_session_payouts po
                WHERE po.company_id = ?
                  AND po.payout_type = 'REFUND'
                  AND po.created_at >= ? AND po.created_at < ?
                """ + fp.sql(), BigDecimal.class, args(companyId, from, to, fp.params()));

        Long cancelled = jdbc.queryForObject("""
                SELECT COUNT(*) FROM restaurant_orders o
                WHERE o.company_id = ?
                  AND o.status = 'CANCELLED'
                  AND o.cancelled_at >= ? AND o.cancelled_at < ?
                """ + fo.sql(), Long.class, args(companyId, from, to, fo.params()));

        BigDecimal voidedItems = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.quantity), 0)
                FROM restaurant_order_lines l
                JOIN restaurant_orders o ON o.id = l.restaurant_order_id
                WHERE o.company_id = ?
                  AND l.status = 'VOIDED'
                  AND l.parent_line_id IS NULL
                  AND l.voided_at >= ? AND l.voided_at < ?
                """ + fo.sql(), BigDecimal.class, args(companyId, from, to, fo.params()));

        return new Kpis(sales, receiptCount, divide(sales, receiptCount),
                ((Number) orders.get("orders")).longValue(), guests,
                divide((BigDecimal) orders.get("seated_sales"), guests), discounts, complimentary,
                refunds, ((Number) receipts.get("voided")).longValue(),
                cancelled == null ? 0 : cancelled, voidedItems);
    }

    private Live live(Long companyId, Filter fo) {
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE o.status = 'OPEN')   AS open_count,
                       COUNT(*) FILTER (WHERE o.status = 'BILLED') AS billed_count,
                       COALESCE(SUM(o.billed_total_amount)
                                FILTER (WHERE o.status = 'BILLED'), 0) AS billed_amount
                FROM restaurant_orders o
                WHERE o.company_id = ?
                  AND o.status IN ('OPEN', 'BILLED')
                """ + fo.sql(), args(companyId, null, null, fo.params()));
        return new Live(((Number) m.get("open_count")).longValue(),
                ((Number) m.get("billed_count")).longValue(), (BigDecimal) m.get("billed_amount"));
    }

    private List<Day> days(Long companyId, Window w, LocalDate fromDate, LocalDate toDate) {
        Map<String, Day> found = new HashMap<>();
        jdbc.query("""
                SELECT to_char(i.finalised_at AT TIME ZONE ?, 'YYYY-MM-DD') AS day,
                       COUNT(*) AS receipts,
                       COALESCE(SUM(""" + receiptGross(w) + """
                ), 0) AS sales
                FROM sales_invoices i
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1
                """,
                rs -> {
                    found.put(rs.getString("day"), new Day(rs.getString("day"),
                            rs.getLong("receipts"), rs.getBigDecimal("sales")));
                },
                zoneArgs(w, companyId));
        // Every day of the range, a quiet one as zero, so the chart's x-axis is honest.
        List<Day> out = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            out.add(found.getOrDefault(d.toString(), new Day(d.toString(), 0, BigDecimal.ZERO)));
        }
        return out;
    }

    private List<Hour> hours(Long companyId, Window w) {
        Map<Integer, Hour> found = new HashMap<>();
        jdbc.query("""
                SELECT CAST(EXTRACT(HOUR FROM i.finalised_at AT TIME ZONE ?) AS INT) AS hour,
                       COUNT(*) AS receipts,
                       COALESCE(SUM(""" + receiptGross(w) + """
                ), 0) AS sales
                FROM sales_invoices i
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1
                """,
                rs -> {
                    found.put(rs.getInt("hour"), new Hour(rs.getInt("hour"),
                            rs.getLong("receipts"), rs.getBigDecimal("sales")));
                },
                zoneArgs(w, companyId));
        List<Hour> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            out.add(found.getOrDefault(h, new Hour(h, 0, BigDecimal.ZERO)));
        }
        return out;
    }

    private List<Weekday> weekdays(Long companyId, Window w, LocalDate fromDate,
                                   LocalDate toDate) {
        Map<Integer, Object[]> found = new HashMap<>();
        jdbc.query("""
                SELECT CAST(EXTRACT(ISODOW FROM i.finalised_at AT TIME ZONE ?) AS INT) AS dow,
                       COUNT(*) AS receipts,
                       COALESCE(SUM(""" + receiptGross(w) + """
                ), 0) AS sales
                FROM sales_invoices i
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1
                """,
                rs -> {
                    found.put(rs.getInt("dow"),
                            new Object[] {rs.getLong("receipts"), rs.getBigDecimal("sales")});
                },
                zoneArgs(w, companyId));
        int[] count = weekdayCounts(fromDate, toDate);
        List<Weekday> out = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            Object[] r = found.getOrDefault(d.getValue(), new Object[] {0L, BigDecimal.ZERO});
            BigDecimal sales = (BigDecimal) r[1];
            int n = count[d.getValue()];
            out.add(new Weekday(d.getValue(), d.getDisplayName(TextStyle.FULL, Locale.ENGLISH), n,
                    (Long) r[0], sales, divide(sales, n)));
        }
        return out;
    }

    /** Departments biggest first, and the best-selling items by amount. */
    private void departmentsAndItems(Long companyId, Window w, List<Department> departments,
                                     List<Item> topItems) {
        Map<String, String> label = new LinkedHashMap<>();
        Map<String, BigDecimal[]> dept = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        jdbc.query("""
                SELECT COALESCE(NULLIF(TRIM(p.category), ''), '""" + NO_DEPARTMENT + """
                ') AS department,
                       COALESCE(p.code, MAX(l.product_code)) AS code,
                       COALESCE(p.name, MAX(l.product_name)) AS name,
                       COALESCE(NULLIF(TRIM(u.symbol), ''), u.code) AS unit,
                       SUM(l.qty_in_base) AS quantity,
                       COALESCE(SUM(""" + lineGross(w) + """
                ), 0) AS sales
                FROM sales_invoice_lines l
                JOIN sales_invoices i ON i.id = l.invoice_id
                LEFT JOIN products p ON p.id = l.product_id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1, l.product_id, p.code, p.name, u.symbol, u.code
                ORDER BY sales DESC, name
                """,
                rs -> {
                    String d = rs.getString("department");
                    String key = d.toLowerCase(Locale.ROOT);
                    label.putIfAbsent(key, d);
                    BigDecimal[] acc = dept.computeIfAbsent(key,
                            k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    acc[0] = acc[0].add(rs.getBigDecimal("quantity"));
                    acc[1] = acc[1].add(rs.getBigDecimal("sales"));
                    if (items.size() < TOP_ITEMS) {
                        items.add(new Item(rs.getString("code"), rs.getString("name"),
                                rs.getString("unit"), rs.getBigDecimal("quantity"),
                                rs.getBigDecimal("sales")));
                    }
                },
                args(companyId, w.from(), w.to(), w.params()));
        for (Map.Entry<String, BigDecimal[]> e : dept.entrySet()) {
            departments.add(new Department(label.get(e.getKey()), e.getValue()[0],
                    e.getValue()[1]));
        }
        departments.sort((a, b) -> b.sales().compareTo(a.sales()));
        topItems.addAll(items);
    }

    private List<RestaurantDashboardDto.Tender> tenders(Long companyId, Window w) {
        return jdbc.query("""
                SELECT p.tender_type AS tender, COUNT(*) AS payments,
                       COALESCE(SUM(""" + paid(w) + """
                ), 0) AS amount
                FROM sales_invoice_payments p
                JOIN sales_invoices i ON i.id = p.invoice_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1
                ORDER BY amount DESC, 1
                """,
                (rs, n) -> new RestaurantDashboardDto.Tender(rs.getString("tender"),
                        rs.getLong("payments"), rs.getBigDecimal("amount")),
                args(companyId, w.from(), w.to(), w.params()));
    }

    private List<Comp> complimentary(Long companyId, Window w) {
        ZoneId zone = w.zone();
        return jdbc.query("""
                SELECT i.invoice_number, i.finalised_at, p.reference,
                       """ + paid(w) + """
                 AS amount
                FROM sales_invoice_payments p
                JOIN sales_invoices i ON i.id = p.invoice_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND p.tender_type = 'COMPLIMENTARY'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                ORDER BY i.finalised_at, i.invoice_number
                """,
                (rs, n) -> new Comp(rs.getString("invoice_number"),
                        RestaurantReportQuery.when(rs, "finalised_at", zone),
                        rs.getString("reference"), rs.getBigDecimal("amount")),
                args(companyId, w.from(), w.to(), w.params()));
    }

    // ---------------------------------------------------------------- by hour and weekday

    public RestaurantDaypartDto daypart(Long companyId, LocalDate fromDate, LocalDate toDate,
                                        String branchUid) {
        Window w = window(companyId, fromDate, toDate, branchUid);
        int span = (int) ChronoUnit.DAYS.between(fromDate, toDate) + 1;

        // Sales per (hour, weekday, department) from the lines; receipts per (hour, weekday).
        Map<String, String> label = new LinkedHashMap<>();
        Map<String, BigDecimal> deptTotal = new HashMap<>();
        Map<Integer, Map<String, BigDecimal>> byHour = new HashMap<>();
        Map<Integer, Map<String, BigDecimal>> byDay = new HashMap<>();
        jdbc.query("""
                SELECT CAST(EXTRACT(HOUR FROM i.finalised_at AT TIME ZONE ?) AS INT) AS hour,
                       CAST(EXTRACT(ISODOW FROM i.finalised_at AT TIME ZONE ?) AS INT) AS dow,
                       COALESCE(NULLIF(TRIM(p.category), ''), '""" + NO_DEPARTMENT + """
                ') AS department,
                       COALESCE(SUM(""" + lineGross(w) + """
                ), 0) AS sales
                FROM sales_invoice_lines l
                JOIN sales_invoices i ON i.id = l.invoice_id
                LEFT JOIN products p ON p.id = l.product_id
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1, 2, 3
                """,
                rs -> {
                    String d = rs.getString("department");
                    String key = d.toLowerCase(Locale.ROOT);
                    label.putIfAbsent(key, d);
                    BigDecimal s = rs.getBigDecimal("sales");
                    deptTotal.merge(key, s, BigDecimal::add);
                    byHour.computeIfAbsent(rs.getInt("hour"), k -> new HashMap<>())
                            .merge(key, s, BigDecimal::add);
                    byDay.computeIfAbsent(rs.getInt("dow"), k -> new HashMap<>())
                            .merge(key, s, BigDecimal::add);
                },
                twoZoneArgs(w, companyId));
        Map<Integer, Long> receiptsByHour = new HashMap<>();
        Map<Integer, Long> receiptsByDay = new HashMap<>();
        long receipts = 0;
        List<Object[]> counts = jdbc.query("""
                SELECT CAST(EXTRACT(HOUR FROM i.finalised_at AT TIME ZONE ?) AS INT) AS hour,
                       CAST(EXTRACT(ISODOW FROM i.finalised_at AT TIME ZONE ?) AS INT) AS dow,
                       COUNT(*) AS receipts
                FROM sales_invoices i
                WHERE i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                GROUP BY 1, 2
                """,
                (rs, n) -> new Object[] {rs.getInt("hour"), rs.getInt("dow"),
                        rs.getLong("receipts")},
                twoZoneArgs(w, companyId));
        for (Object[] c : counts) {
            receiptsByHour.merge((Integer) c[0], (Long) c[2], Long::sum);
            receiptsByDay.merge((Integer) c[1], (Long) c[2], Long::sum);
            receipts += (Long) c[2];
        }

        List<String> keys = new ArrayList<>(deptTotal.keySet());
        keys.sort((a, b) -> {
            int c = deptTotal.get(b).compareTo(deptTotal.get(a));
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> departments = keys.stream().map(label::get).toList();

        List<Slot> hours = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            hours.add(slot(String.format("%02d:00-%02d:00", h, (h + 1) % 24),
                    receiptsByHour.getOrDefault(h, 0L), byHour.get(h), keys, span));
        }
        int[] dayCount = weekdayCounts(fromDate, toDate);
        List<Slot> weekdays = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            weekdays.add(slot(d.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    receiptsByDay.getOrDefault(d.getValue(), 0L), byDay.get(d.getValue()), keys,
                    dayCount[d.getValue()]));
        }
        return new RestaurantDaypartDto(w.header().dto(), fromDate.toString(), toDate.toString(),
                w.branchName(), w.header().baseCurrency(), departments, hours, weekdays,
                slot("TOTAL", receipts, deptTotal, keys, span), Instant.now().toString());
    }

    private static Slot slot(String label, long receipts, Map<String, BigDecimal> sales,
                             List<String> keys, int days) {
        List<BigDecimal> perDept = new ArrayList<>(keys.size());
        BigDecimal total = BigDecimal.ZERO;
        for (String k : keys) {
            BigDecimal s = sales == null ? BigDecimal.ZERO
                    : sales.getOrDefault(k, BigDecimal.ZERO);
            perDept.add(s);
            total = total.add(s);
        }
        return new Slot(label, receipts, total, days, divide(total, days), perDept);
    }

    // ---------------------------------------------------------------- payments and completion

    public RestaurantPaymentsReportDto payments(Long companyId, LocalDate fromDate,
                                                LocalDate toDate, String branchUid) {
        Window w = window(companyId, fromDate, toDate, branchUid);
        Filter fo = reports.branchFilter(companyId, branchUid, "o.branch_id");
        Filter fp = reports.branchFilter(companyId, branchUid, "po.branch_id");
        ZoneId zone = w.zone();

        List<RestaurantDashboardDto.Tender> raw = tenders(companyId, w);
        BigDecimal totalPaid = BigDecimal.ZERO;
        for (RestaurantDashboardDto.Tender t : raw) {
            totalPaid = totalPaid.add(t.amount());
        }
        List<RestaurantPaymentsReportDto.Tender> tenders = new ArrayList<>();
        for (RestaurantDashboardDto.Tender t : raw) {
            tenders.add(new RestaurantPaymentsReportDto.Tender(t.tenderType(), t.payments(),
                    t.amount(), percent(t.amount(), totalPaid)));
        }

        Map<String, Object> c = jdbc.queryForMap("""
                SELECT COUNT(*) AS opened,
                       COUNT(*) FILTER (WHERE o.status = 'PAID')      AS paid,
                       COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled,
                       COUNT(*) FILTER (WHERE o.status IN ('OPEN', 'BILLED')) AS pending
                FROM restaurant_orders o
                WHERE o.company_id = ?
                  AND o.opened_at >= ? AND o.opened_at < ?
                """ + fo.sql(), args(companyId, w.from(), w.to(), fo.params()));
        long opened = ((Number) c.get("opened")).longValue();
        long paidOrders = ((Number) c.get("paid")).longValue();
        RestaurantPaymentsReportDto.Completion completion =
                new RestaurantPaymentsReportDto.Completion(opened, paidOrders,
                        ((Number) c.get("cancelled")).longValue(),
                        ((Number) c.get("pending")).longValue(),
                        opened == 0 ? null : percent(BigDecimal.valueOf(paidOrders),
                                BigDecimal.valueOf(opened)));

        List<RestaurantPaymentsReportDto.Refund> refunds = jdbc.query("""
                SELECT po.created_at, i.invoice_number, po.reason, u.display_name AS by_name,
                       po.amount
                FROM pos_session_payouts po
                LEFT JOIN sales_invoices i ON i.uid = po.source_invoice_uid
                LEFT JOIN app_users u      ON u.id = po.created_by
                WHERE po.company_id = ?
                  AND po.payout_type = 'REFUND'
                  AND po.created_at >= ? AND po.created_at < ?
                """ + fp.sql() + """

                ORDER BY po.created_at
                """,
                (rs, n) -> new RestaurantPaymentsReportDto.Refund(
                        RestaurantReportQuery.when(rs, "created_at", zone),
                        rs.getString("invoice_number"), rs.getString("reason"),
                        rs.getString("by_name"), rs.getBigDecimal("amount")),
                args(companyId, w.from(), w.to(), fp.params()));
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (RestaurantPaymentsReportDto.Refund r : refunds) {
            refundTotal = refundTotal.add(r.amount());
        }

        List<RestaurantPaymentsReportDto.VoidedReceipt> voided = jdbc.query("""
                SELECT i.finalised_at, i.invoice_number,
                       COALESCE(uv.display_name, uc.display_name) AS by_name,
                       """ + receiptGross(w) + """
                 AS amount
                FROM sales_invoices i
                LEFT JOIN app_users uv ON uv.id = i.updated_by
                LEFT JOIN app_users uc ON uc.id = i.created_by
                WHERE i.company_id = ?
                  AND i.status = 'VOID'
                  AND i.finalised_at >= ? AND i.finalised_at < ?
                """ + w.filterSql() + """

                ORDER BY i.finalised_at
                """,
                (rs, n) -> new RestaurantPaymentsReportDto.VoidedReceipt(
                        RestaurantReportQuery.when(rs, "finalised_at", zone),
                        rs.getString("invoice_number"), rs.getString("by_name"),
                        rs.getBigDecimal("amount")),
                args(companyId, w.from(), w.to(), w.params()));
        BigDecimal voidedTotal = BigDecimal.ZERO;
        for (RestaurantPaymentsReportDto.VoidedReceipt v : voided) {
            voidedTotal = voidedTotal.add(v.amount());
        }

        List<RestaurantPaymentsReportDto.CancelledOrder> cancelled = jdbc.query("""
                SELECT o.order_number, """ + SERVED_AT + """
                 AS served_at,
                       u.display_name AS waiter_name, o.opened_at, o.cancelled_at,
                       o.cancel_reason,
                       (SELECT COALESCE(SUM(l.quantity), 0) FROM restaurant_order_lines l
                         WHERE l.restaurant_order_id = o.id
                           AND l.parent_line_id IS NULL) AS items
                FROM restaurant_orders o
                JOIN app_users u              ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN restaurant_tables t ON t.id = o.restaurant_table_id
                WHERE o.company_id = ?
                  AND o.status = 'CANCELLED'
                  AND o.cancelled_at >= ? AND o.cancelled_at < ?
                """ + fo.sql() + """

                ORDER BY o.cancelled_at
                """,
                (rs, n) -> new RestaurantPaymentsReportDto.CancelledOrder(
                        rs.getString("order_number"), rs.getString("served_at"),
                        rs.getString("waiter_name"),
                        RestaurantReportQuery.when(rs, "opened_at", zone),
                        RestaurantReportQuery.when(rs, "cancelled_at", zone),
                        rs.getString("cancel_reason"), rs.getBigDecimal("items")),
                args(companyId, w.from(), w.to(), fo.params()));

        List<RestaurantPaymentsReportDto.VoidedItem> voidedItems = jdbc.query("""
                SELECT l.voided_at, o.order_number, """ + SERVED_AT + """
                 AS served_at,
                       u.display_name AS waiter_name, p.name AS product_name, l.quantity,
                       l.void_reason
                FROM restaurant_order_lines l
                JOIN restaurant_orders o      ON o.id = l.restaurant_order_id
                JOIN app_users u              ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN products p          ON p.id = l.product_id
                LEFT JOIN restaurant_tables t ON t.id = o.restaurant_table_id
                WHERE o.company_id = ?
                  AND l.status = 'VOIDED'
                  AND l.parent_line_id IS NULL
                  AND l.voided_at >= ? AND l.voided_at < ?
                """ + fo.sql() + """

                ORDER BY l.voided_at
                """,
                (rs, n) -> new RestaurantPaymentsReportDto.VoidedItem(
                        RestaurantReportQuery.when(rs, "voided_at", zone),
                        rs.getString("order_number"), rs.getString("served_at"),
                        rs.getString("waiter_name"), rs.getString("product_name"),
                        rs.getBigDecimal("quantity"), rs.getString("void_reason")),
                args(companyId, w.from(), w.to(), fo.params()));

        return new RestaurantPaymentsReportDto(w.header().dto(), fromDate.toString(),
                toDate.toString(), w.branchName(), w.header().baseCurrency(), tenders, totalPaid,
                completion, refunds, refundTotal, voided, voidedTotal, cancelled, voidedItems,
                Instant.now().toString());
    }

    // ---------------------------------------------------------------- shared

    /** Where the order was served: its table, or Takeaway. */
    private static final String SERVED_AT =
            "COALESCE(t.name, CASE WHEN o.order_type = 'TAKEAWAY' THEN 'Takeaway' END)";

    private Window window(Long companyId, LocalDate fromDate, LocalDate toDate, String branchUid) {
        Window w = reports.window(companyId, fromDate, toDate, null, branchUid, "i.branch_id");
        if (ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_DAYS) {
            throw new IllegalArgumentException("Choose a range of one year or less.");
        }
        return w;
    }

    private static String receiptGross(Window w) {
        return BaseCurrencySql.toBase("i.gross_total_amount", "i.fx_rate", w.baseScale());
    }

    private static String lineGross(Window w) {
        return BaseCurrencySql.grossToBase("l.net_amount", "l.vat_amount", "i.fx_rate",
                w.baseScale());
    }

    /** What a payment really paid: the amount handed over less any change. */
    private static String paid(Window w) {
        return BaseCurrencySql.toBase("(p.amount - COALESCE(p.change_amount, 0))", "i.fx_rate",
                w.baseScale());
    }

    /** {@code companyId, from, to, filter params…}; null bounds are left out. */
    private static Object[] args(Long companyId, OffsetDateTime from, OffsetDateTime to,
                                 List<Object> filterParams) {
        List<Object> a = new ArrayList<>();
        a.add(companyId);
        if (from != null) {
            a.add(from);
            a.add(to);
        }
        a.addAll(filterParams);
        return a.toArray();
    }

    private static Object[] zoneArgs(Window w, Long companyId) {
        List<Object> a = new ArrayList<>();
        a.add(w.zone().getId());
        a.addAll(List.of(args(companyId, w.from(), w.to(), w.params())));
        return a.toArray();
    }

    private static Object[] twoZoneArgs(Window w, Long companyId) {
        List<Object> a = new ArrayList<>();
        a.add(w.zone().getId());
        a.addAll(List.of(zoneArgs(w, companyId)));
        return a.toArray();
    }

    /** How many Mondays, Tuesdays… the range holds; index 1 = Monday. */
    static int[] weekdayCounts(LocalDate fromDate, LocalDate toDate) {
        int[] count = new int[8];
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            count[d.getDayOfWeek().getValue()]++;
        }
        return count;
    }

    private static BigDecimal divide(BigDecimal amount, long by) {
        return by == 0 || amount == null ? BigDecimal.ZERO
                : amount.divide(BigDecimal.valueOf(by), 2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO
                : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
