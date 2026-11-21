package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.RestaurantBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsSummaryDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingSummaryDto;
import com.erp.modules.sales.domain.dto.WaiterItemsReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesRowDto;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.BranchReadScope;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The restaurant reports per waiter (J COB 2026-10-30): sales, paid bills and pending bills. One
 * waiter or all of them; one branch or every branch the caller may read.
 *
 * <p>The waiter is whoever holds the order: the last waiter it was transferred to
 * ({@code served_by}, ADR-0067), else whoever opened it — the waiter signed in on the till, or the
 * cashier when nobody else was (ADR-0066 D-8). A bill counts for the waiter holding it when paid. The cashier is the
 * till session's cashier, else whoever finalised the receipt. Money is the receipt's gross in the
 * company's base currency ({@link BaseCurrencySql}); a voided receipt is listed but never summed.
 * Scalar cross-module reads by native SQL, the same pattern as {@link SalesReportQuery}.
 */
@Component
@Transactional(readOnly = true)
public class RestaurantReportQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** The department of an item with no category, as the reports show and filter it. */
    static final String NO_DEPARTMENT = "(no department)";

    /** Where the order was served: its table, or Takeaway. */
    private static final String SERVED_AT =
            "COALESCE(t.name, CASE WHEN o.order_type = 'TAKEAWAY' THEN 'Takeaway' END)";

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;

    public RestaurantReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
                                 BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
    }

    // ---------------------------------------------------------------- waiter sales

    public WaiterSalesReportDto waiterSales(Long companyId, LocalDate fromDate, LocalDate toDate,
                                            String waiterUid, String branchUid) {
        return waiterSales(companyId, fromDate, toDate, waiterUid, branchUid, ItemFilter.NONE);
    }

    /**
     * Bill grain: with an item or department filter, the bills that hold a matching line, each at
     * its whole amount.
     */
    public WaiterSalesReportDto waiterSales(Long companyId, LocalDate fromDate, LocalDate toDate,
                                            String waiterUid, String branchUid, ItemFilter items) {
        return waiterSales(companyId, fromDate, toDate, waiterUid, branchUid, items, false);
    }

    /**
     * The dashboard's waiters: like {@link #waiterSales}, but with an item or department filter
     * each bill counts only the matching lines' amount, so the waiters add up to the dashboard's
     * line-level sales. Without a filter it is exactly {@link #waiterSales}.
     */
    public WaiterSalesReportDto waiterLineSales(Long companyId, LocalDate fromDate,
                                                LocalDate toDate, String waiterUid,
                                                String branchUid, ItemFilter items) {
        return waiterSales(companyId, fromDate, toDate, waiterUid, branchUid, items, true);
    }

    private WaiterSalesReportDto waiterSales(Long companyId, LocalDate fromDate, LocalDate toDate,
                                             String waiterUid, String branchUid, ItemFilter items,
                                             boolean lineAmounts) {
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid, "o.branch_id", items);
        boolean lines = lineAmounts && w.items.active();
        String amount = lines ? "m.gross"
                : BaseCurrencySql.toBase("i.gross_total_amount", "i.fx_rate", w.baseScale);
        String matched = lines ? w.items.matchedGrossJoin(w.baseScale) : "";
        List<Object> params = new ArrayList<>();
        if (lines) {
            params.addAll(w.items.params());
        }
        params.addAll(List.of(companyId, w.from, w.to));
        params.addAll(w.params);
        List<WaiterSalesRowDto> rows = jdbc.query("""
                SELECT u.uid AS waiter_uid, u.display_name AS waiter_name,
                       COUNT(*)                          AS bills,
                       COALESCE(SUM(o.guest_count), 0)   AS guests,
                       COALESCE(SUM(""" + amount + """
                ), 0) AS amount
                FROM restaurant_orders o
                JOIN sales_invoices i ON i.id = o.sales_invoice_id
                JOIN app_users u      ON u.id = COALESCE(o.served_by, o.opened_by)
                """ + matched + """

                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND i.status = 'FINALISED'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + w.filterSql + """

                GROUP BY u.uid, u.display_name
                ORDER BY amount DESC, u.display_name
                """,
                (rs, i) -> waiterRow(rs.getString("waiter_uid"), rs.getString("waiter_name"),
                        rs.getLong("bills"), rs.getLong("guests"), rs.getBigDecimal("amount")),
                params.toArray());

        long bills = 0;
        long guests = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (WaiterSalesRowDto r : rows) {
            bills += r.bills();
            guests += r.guests();
            total = total.add(r.amount());
        }
        return new WaiterSalesReportDto(w.header.dto(), fromDate.toString(), toDate.toString(),
                w.waiterName, w.header.baseCurrency, rows,
                waiterRow(null, "TOTAL", bills, guests, total), Instant.now().toString());
    }

    private static WaiterSalesRowDto waiterRow(String uid, String name, long bills, long guests,
                                               BigDecimal amount) {
        BigDecimal avg = bills == 0 ? BigDecimal.ZERO
                : amount.divide(BigDecimal.valueOf(bills), 2, RoundingMode.HALF_EVEN);
        return new WaiterSalesRowDto(uid, name, bills, guests, amount, avg);
    }

    // ---------------------------------------------------------------- bills

    public RestaurantBillsReportDto bills(Long companyId, LocalDate fromDate, LocalDate toDate,
                                          String waiterUid, String branchUid) {
        return bills(companyId, fromDate, toDate, waiterUid, branchUid, ItemFilter.NONE);
    }

    /** Bill grain: with an item or department filter, the bills holding a matching line. */
    public RestaurantBillsReportDto bills(Long companyId, LocalDate fromDate, LocalDate toDate,
                                          String waiterUid, String branchUid, ItemFilter items) {
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid, "o.branch_id", items);
        String amount = BaseCurrencySql.toBase("i.gross_total_amount", "i.fx_rate", w.baseScale);
        List<Object> params = new ArrayList<>(List.of(companyId, w.from, w.to));
        params.addAll(w.params);
        ZoneId zone = w.zone;
        List<RestaurantBillRowDto> rows = jdbc.query("""
                SELECT o.uid, o.order_number, """ + SERVED_AT + """
                 AS served_at,
                       u.display_name AS waiter_name,
                       COALESCE(cs.display_name, cf.display_name) AS cashier_name,
                       i.invoice_number, i.status AS invoice_status, o.guest_count,
                       o.opened_at, o.paid_at,
                       """ + amount + """
                 AS amount,
                       (SELECT string_agg(DISTINCT p.tender_type, ', ' ORDER BY p.tender_type)
                          FROM sales_invoice_payments p WHERE p.invoice_id = i.id) AS tenders
                FROM restaurant_orders o
                JOIN sales_invoices i       ON i.id = o.sales_invoice_id
                JOIN app_users u            ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN restaurant_tables t ON t.id = o.restaurant_table_id
                LEFT JOIN pos_sessions ps   ON ps.id = i.pos_session_id
                LEFT JOIN app_users cs      ON cs.id = ps.cashier_id
                LEFT JOIN app_users cf      ON cf.id = i.created_by
                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + w.filterSql + """

                ORDER BY o.paid_at, o.order_number
                """,
                (rs, i) -> new RestaurantBillRowDto(
                        rs.getString("uid"), rs.getString("order_number"),
                        rs.getString("served_at"), rs.getString("waiter_name"),
                        rs.getString("cashier_name"), rs.getString("invoice_number"),
                        rs.getString("invoice_status"), intOrNull(rs, "guest_count"),
                        when(rs, "opened_at", zone), when(rs, "paid_at", zone),
                        rs.getBigDecimal("amount"), rs.getString("tenders")),
                params.toArray());

        long count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (RestaurantBillRowDto r : rows) {
            if ("FINALISED".equals(r.invoiceStatus())) {
                count++;
                total = total.add(r.amount());
            }
        }
        return new RestaurantBillsReportDto(w.header.dto(), fromDate.toString(), toDate.toString(),
                w.waiterName, w.header.baseCurrency, rows, count, total,
                Instant.now().toString());
    }

    // ---------------------------------------------------------------- pending bills

    /** Every order not yet paid, now: OPEN, or BILLED and waiting for payment. No date window. */
    public RestaurantPendingBillsReportDto pendingBills(Long companyId, String waiterUid,
                                                        String branchUid) {
        return pendingBills(companyId, waiterUid, branchUid, ItemFilter.NONE);
    }

    /**
     * Bill grain: with an item or department filter, the orders holding a matching item that is
     * not voided; each still shows its whole bill.
     */
    public RestaurantPendingBillsReportDto pendingBills(Long companyId, String waiterUid,
                                                        String branchUid, ItemFilter items) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        Header header = loadHeader(companyId);
        ZoneId zone = header.zone();
        Filter f = filter(principal, companyId, waiterUid, branchUid, "o.branch_id");
        ItemScope scope = itemScope(companyId, items);
        List<Object> params = new ArrayList<>(List.of(companyId));
        params.addAll(f.params);
        params.addAll(scope.params());
        List<RestaurantPendingBillRowDto> rows = jdbc.query("""
                SELECT o.uid, o.order_number, """ + SERVED_AT + """
                 AS served_at,
                       u.display_name AS waiter_name, o.status, o.opened_at, o.billed_at,
                       (SELECT COALESCE(SUM(l.quantity), 0) FROM restaurant_order_lines l
                         WHERE l.restaurant_order_id = o.id
                           AND l.status <> 'VOIDED'
                           AND l.parent_line_id IS NULL) AS items,
                       o.billed_total_amount
                FROM restaurant_orders o
                JOIN app_users u              ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN restaurant_tables t ON t.id = o.restaurant_table_id
                WHERE o.company_id = ?
                  AND o.status IN ('OPEN', 'BILLED')
                """ + f.sql + scope.orderExists("o.id", true) + """

                ORDER BY o.opened_at, o.order_number
                """,
                (rs, i) -> new RestaurantPendingBillRowDto(
                        rs.getString("uid"), rs.getString("order_number"),
                        rs.getString("served_at"), rs.getString("waiter_name"),
                        rs.getString("status"), when(rs, "opened_at", zone),
                        when(rs, "billed_at", zone), rs.getBigDecimal("items"),
                        rs.getBigDecimal("billed_total_amount")),
                params.toArray());

        long open = 0;
        long billed = 0;
        BigDecimal billedAmount = BigDecimal.ZERO;
        for (RestaurantPendingBillRowDto r : rows) {
            if ("BILLED".equals(r.status())) {
                billed++;
                if (r.billedAmount() != null) {
                    billedAmount = billedAmount.add(r.billedAmount());
                }
            } else {
                open++;
            }
        }
        return new RestaurantPendingBillsReportDto(header.dto(), f.waiterName, header.baseCurrency,
                rows, open, billed, billedAmount, Instant.now().toString());
    }

    // ---------------------------------------------------------------- waiter items (detail)

    /** The items on each waiter's paid bills: sales by waiter in detail. */
    public WaiterItemsReportDto waiterItems(Long companyId, LocalDate fromDate, LocalDate toDate,
                                            String waiterUid, String branchUid) {
        return waiterItems(companyId, fromDate, toDate, waiterUid, branchUid, ItemFilter.NONE);
    }

    /**
     * Line grain: with an item or department filter, only the matching lines; a waiter's bills
     * are the bills holding one.
     */
    public WaiterItemsReportDto waiterItems(Long companyId, LocalDate fromDate, LocalDate toDate,
                                            String waiterUid, String branchUid, ItemFilter items) {
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid, "o.branch_id", items);
        String gross = BaseCurrencySql.grossToBase("l.net_amount", "l.vat_amount", "i.fx_rate",
                w.baseScale);
        List<Object> params = new ArrayList<>(List.of(companyId, w.from, w.to));
        params.addAll(w.params);
        List<Object> lineParams = new ArrayList<>(params);
        lineParams.addAll(w.items.params());
        List<WaiterItemsReportDto.Row> lines = jdbc.query("""
                SELECT u.uid AS waiter_uid, u.display_name AS waiter_name,
                       COALESCE(p.code, MAX(l.product_code)) AS code,
                       COALESCE(p.name, MAX(l.product_name)) AS name,
                       MAX(l.unit_name) AS unit,
                       SUM(l.quantity) AS quantity,
                       COALESCE(SUM(""" + gross + """
                ), 0) AS amount
                FROM restaurant_orders o
                JOIN sales_invoices i      ON i.id = o.sales_invoice_id
                JOIN sales_invoice_lines l ON l.invoice_id = i.id
                JOIN app_users u           ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN products p       ON p.id = l.product_id
                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND i.status = 'FINALISED'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + w.filterSql + w.items.lineSql("l", "p") + """

                GROUP BY u.uid, u.display_name, l.product_id, l.unit_id, p.code, p.name
                ORDER BY amount DESC, name
                """,
                (rs, i) -> new WaiterItemsReportDto.Row(rs.getString("waiter_uid"),
                        rs.getString("waiter_name"), rs.getString("code"), rs.getString("name"),
                        rs.getString("unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("amount")),
                lineParams.toArray());

        // Waiters biggest first, by the same lines; bills counted from the orders.
        Map<String, Long> bills = new HashMap<>();
        jdbc.query("""
                SELECT u.uid AS waiter_uid, COUNT(*) AS bills
                FROM restaurant_orders o
                JOIN sales_invoices i ON i.id = o.sales_invoice_id
                JOIN app_users u      ON u.id = COALESCE(o.served_by, o.opened_by)
                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND i.status = 'FINALISED'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + w.filterSql + """

                GROUP BY u.uid
                """,
                rs -> {
                    bills.put(rs.getString("waiter_uid"), rs.getLong("bills"));
                },
                params.toArray());
        Map<String, WaiterItemsReportDto.Subtotal> subtotals =
                new LinkedHashMap<>();
        for (WaiterItemsReportDto.Row r : lines) {
            WaiterItemsReportDto.Subtotal s = subtotals.get(r.waiterUid());
            BigDecimal before = s == null ? BigDecimal.ZERO : s.amount();
            subtotals.put(r.waiterUid(), new WaiterItemsReportDto.Subtotal(r.waiterUid(),
                    r.waiterName(), bills.getOrDefault(r.waiterUid(), 0L),
                    before.add(r.amount())));
        }
        List<WaiterItemsReportDto.Subtotal> waiters = new ArrayList<>(subtotals.values());
        waiters.sort((a, b) -> {
            int c = b.amount().compareTo(a.amount());
            return c != 0 ? c : a.waiterName().compareToIgnoreCase(b.waiterName());
        });
        List<WaiterItemsReportDto.Row> rows = new ArrayList<>(lines.size());
        BigDecimal total = BigDecimal.ZERO;
        for (WaiterItemsReportDto.Subtotal s : waiters) {
            for (WaiterItemsReportDto.Row r : lines) {
                if (r.waiterUid().equals(s.waiterUid())) {
                    rows.add(r);
                }
            }
            total = total.add(s.amount());
        }
        return new WaiterItemsReportDto(w.header.dto(), fromDate.toString(), toDate.toString(),
                w.waiterName, w.header.baseCurrency, rows, waiters, total,
                Instant.now().toString());
    }

    // ---------------------------------------------------------------- bills (summary)

    /** Bills paid per waiter, the amount split by how it was paid. Voided receipts left out. */
    public RestaurantBillsSummaryDto billsSummary(Long companyId, LocalDate fromDate,
                                                  LocalDate toDate, String waiterUid,
                                                  String branchUid) {
        return billsSummary(companyId, fromDate, toDate, waiterUid, branchUid, ItemFilter.NONE);
    }

    /** Bill grain: with an item or department filter, the bills holding a matching line. */
    public RestaurantBillsSummaryDto billsSummary(Long companyId, LocalDate fromDate,
                                                  LocalDate toDate, String waiterUid,
                                                  String branchUid, ItemFilter items) {
        WaiterSalesReportDto sales =
                waiterSales(companyId, fromDate, toDate, waiterUid, branchUid, items);
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid, "o.branch_id", items);
        String paid = BaseCurrencySql.toBase("(p.amount - COALESCE(p.change_amount, 0))",
                "i.fx_rate", w.baseScale);
        List<Object> params = new ArrayList<>(List.of(companyId, w.from, w.to));
        params.addAll(w.params);
        // waiter uid -> tender -> amount
        Map<String, Map<String, BigDecimal>> byWaiter = new HashMap<>();
        Set<String> seen = new HashSet<>();
        jdbc.query("""
                SELECT u.uid AS waiter_uid, p.tender_type AS tender,
                       COALESCE(SUM(""" + paid + """
                ), 0) AS amount
                FROM restaurant_orders o
                JOIN sales_invoices i          ON i.id = o.sales_invoice_id
                JOIN sales_invoice_payments p  ON p.invoice_id = i.id
                JOIN app_users u               ON u.id = COALESCE(o.served_by, o.opened_by)
                WHERE o.company_id = ?
                  AND o.status = 'PAID'
                  AND i.status = 'FINALISED'
                  AND o.paid_at >= ? AND o.paid_at < ?
                """ + w.filterSql + """

                GROUP BY u.uid, p.tender_type
                """,
                rs -> {
                    String tender = rs.getString("tender");
                    seen.add(tender);
                    byWaiter.computeIfAbsent(rs.getString("waiter_uid"),
                            k -> new HashMap<>()).put(tender, rs.getBigDecimal("amount"));
                },
                params.toArray());

        // Payment types in their usual order, only those used; any unknown type last.
        List<String> tenderTypes = new ArrayList<>();
        for (TenderType t
                : TenderType.values()) {
            if (seen.remove(t.name())) {
                tenderTypes.add(t.name());
            }
        }
        seen.stream().sorted().forEach(tenderTypes::add);

        List<RestaurantBillsSummaryDto.Row> rows = new ArrayList<>();
        BigDecimal[] totals = new BigDecimal[tenderTypes.size()];
        Arrays.fill(totals, BigDecimal.ZERO);
        for (WaiterSalesRowDto r : sales.rows()) {
            Map<String, BigDecimal> mine = byWaiter.getOrDefault(r.waiterUid(),
                    Map.of());
            List<BigDecimal> tenders = new ArrayList<>();
            for (int t = 0; t < tenderTypes.size(); t++) {
                BigDecimal a = mine.getOrDefault(tenderTypes.get(t), BigDecimal.ZERO);
                tenders.add(a);
                totals[t] = totals[t].add(a);
            }
            rows.add(new RestaurantBillsSummaryDto.Row(r.waiterUid(), r.waiterName(), r.bills(),
                    r.guests(), tenders, r.amount()));
        }
        WaiterSalesRowDto t = sales.totals();
        return new RestaurantBillsSummaryDto(sales.company(), sales.fromDate(), sales.toDate(),
                sales.waiterName(), sales.currency(), tenderTypes, rows,
                new RestaurantBillsSummaryDto.Row(null, "TOTAL", t.bills(), t.guests(),
                        List.of(totals), t.amount()),
                Instant.now().toString());
    }

    // ---------------------------------------------------------------- pending (summary)

    /** Pending bills right now, per waiter. No date window, like the detail. */
    public RestaurantPendingSummaryDto pendingSummary(Long companyId, String waiterUid,
                                                      String branchUid) {
        return pendingSummary(companyId, waiterUid, branchUid, ItemFilter.NONE);
    }

    /** Bill grain, like {@link #pendingBills(Long, String, String, ItemFilter)}. */
    public RestaurantPendingSummaryDto pendingSummary(Long companyId, String waiterUid,
                                                      String branchUid, ItemFilter itemFilter) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        Header header = loadHeader(companyId);
        ZoneId zone = header.zone();
        Filter f = filter(principal, companyId, waiterUid, branchUid, "o.branch_id");
        ItemScope scope = itemScope(companyId, itemFilter);
        List<Object> params = new ArrayList<>(List.of(companyId));
        params.addAll(f.params);
        params.addAll(scope.params());
        List<RestaurantPendingSummaryDto.Row> rows = jdbc.query("""
                SELECT u.uid AS waiter_uid, u.display_name AS waiter_name,
                       COUNT(*) FILTER (WHERE o.status = 'OPEN')   AS open_count,
                       COUNT(*) FILTER (WHERE o.status = 'BILLED') AS billed_count,
                       COALESCE(SUM(li.items), 0) AS items,
                       COALESCE(SUM(o.billed_total_amount)
                                FILTER (WHERE o.status = 'BILLED'), 0) AS billed_amount,
                       MIN(o.opened_at) AS oldest
                FROM restaurant_orders o
                JOIN app_users u ON u.id = COALESCE(o.served_by, o.opened_by)
                LEFT JOIN LATERAL (
                    SELECT SUM(l.quantity) AS items FROM restaurant_order_lines l
                     WHERE l.restaurant_order_id = o.id
                       AND l.status <> 'VOIDED'
                       AND l.parent_line_id IS NULL) li ON true
                WHERE o.company_id = ?
                  AND o.status IN ('OPEN', 'BILLED')
                """ + f.sql + scope.orderExists("o.id", true) + """

                GROUP BY u.uid, u.display_name
                ORDER BY billed_amount DESC, u.display_name
                """,
                (rs, i) -> new RestaurantPendingSummaryDto.Row(rs.getString("waiter_uid"),
                        rs.getString("waiter_name"), rs.getLong("open_count"),
                        rs.getLong("billed_count"), rs.getBigDecimal("items"),
                        rs.getBigDecimal("billed_amount"), when(rs, "oldest", zone)),
                params.toArray());

        long open = 0;
        long billed = 0;
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        String oldest = null;
        for (RestaurantPendingSummaryDto.Row r : rows) {
            open += r.open();
            billed += r.billed();
            items = items.add(r.items());
            amount = amount.add(r.billedAmount());
            if (r.oldestOpenedAt() != null
                    && (oldest == null || r.oldestOpenedAt().compareTo(oldest) < 0)) {
                oldest = r.oldestOpenedAt();
            }
        }
        return new RestaurantPendingSummaryDto(header.dto(), f.waiterName, header.baseCurrency,
                rows, new RestaurantPendingSummaryDto.Row(null, "TOTAL", open, billed, items,
                        amount, oldest),
                Instant.now().toString());
    }

    /** The branch filter alone, on {@code branchColumn}, for queries outside this class. */
    public Filter branchFilter(Long companyId, String branchUid, String branchColumn) {
        return filter(RequestContext.get(), companyId, null, branchUid, branchColumn);
    }

    /**
     * The waiter and branch filters on {@code branchColumn}, for queries outside this class; the
     * waiter test follows the table the column belongs to (see {@link #waiterSql}).
     */
    public Filter waiterBranchFilter(Long companyId, String waiterUid, String branchUid,
                                     String branchColumn) {
        return filter(RequestContext.get(), companyId, waiterUid, branchUid, branchColumn);
    }

    // ---------------------------------------------------------------- shared

    record Header(String name, String legalName, String taxId, String vrn,
                          String contactPhone, String contactEmail, String addressLine1,
                          String addressLine2, String city, String region, String country,
                          String timeZone, String baseCurrency) {
        ReportCompanyHeaderDto dto() {
            return new ReportCompanyHeaderDto(name, legalName, addressLine1, addressLine2, city,
                    region, country, contactPhone, contactEmail, taxId, vrn);
        }

        ZoneId zone() {
            return ZoneId.of(timeZone != null ? timeZone : DEFAULT_TIME_ZONE);
        }
    }

    /** The waiter and branch filters, as SQL with their parameters. */
    record Filter(String sql, List<Object> params, String waiterName, String branchName) {}

    /**
     * The date window and filters. {@code filterSql} (with {@code params}) holds the branch and
     * waiter filters and, when an item or department filter is set, the bill-grain test that the
     * receipt {@code i} holds a matching line; a line-grain query adds
     * {@code items.lineSql(...)} on its own lines as well.
     */
    record Window(Header header, ZoneId zone, OffsetDateTime from, OffsetDateTime to,
                  String filterSql, List<Object> params, String waiterName, String branchName,
                  int baseScale, ItemScope items) {}

    /**
     * The date window and filters; {@code branchColumn} is the branch column the SQL filters on.
     * Public, like {@link #branchFilter}, so a call through the transactional proxy reaches the
     * target rather than the proxy's empty fields.
     */
    public Window window(Long companyId, LocalDate fromDate, LocalDate toDate, String waiterUid,
                  String branchUid, String branchColumn) {
        return window(companyId, fromDate, toDate, waiterUid, branchUid, branchColumn,
                ItemFilter.NONE);
    }

    /** As above, with the optional item and department filters (see {@link Window}). */
    public Window window(Long companyId, LocalDate fromDate, LocalDate toDate, String waiterUid,
                  String branchUid, String branchColumn, ItemFilter items) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }
        Header header = loadHeader(companyId);
        ZoneId zone = header.zone();
        Filter f = filter(principal, companyId, waiterUid, branchUid, branchColumn);
        ItemScope scope = itemScope(companyId, items);
        List<Object> params = new ArrayList<>(f.params);
        params.addAll(scope.params());
        return new Window(header, zone,
                fromDate.atStartOfDay(zone).toOffsetDateTime(),
                toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                f.sql + scope.invoiceExists("i.id"), params, f.waiterName, f.branchName,
                BaseCurrencySql.baseScale(jdbc, companyId), scope);
    }

    // ---------------------------------------------------------------- item / department filters

    /**
     * The item and department filters as the caller sent them; either or both may be null or
     * blank. The department is the product's category, matched case-insensitively and trimmed,
     * the way the reports group it; {@value #NO_DEPARTMENT} matches items with none.
     */
    public record ItemFilter(String productUid, String department) {
        public static final ItemFilter NONE = new ItemFilter(null, null);
    }

    /**
     * {@link ItemFilter} resolved: the product id (looked up in the caller's company, never taken
     * from the caller) and the department, lower-cased for the match. Every value is bound as a
     * parameter; {@link #params()} goes with each SQL fragment, in the fragment's place.
     */
    public record ItemScope(Long productId, String productName, String department) {
        static final ItemScope NONE = new ItemScope(null, null, null);

        public boolean active() {
            return productId != null || department != null;
        }

        /** The bound values of any one fragment below: product id, then department. */
        public List<Object> params() {
            List<Object> p = new ArrayList<>(2);
            if (productId != null) {
                p.add(productId);
            }
            if (department != null) {
                p.add(department.toLowerCase(Locale.ROOT));
            }
            return p;
        }

        /**
         * {@code AND …} on one line ({@code line}: sales_invoice_lines or restaurant_order_lines)
         * and its product ({@code product}, LEFT JOINed). Empty when no filter is set.
         */
        public String lineSql(String line, String product) {
            StringBuilder sql = new StringBuilder();
            if (productId != null) {
                sql.append(" AND ").append(line).append(".product_id = ?");
            }
            if (department != null) {
                sql.append(" AND LOWER(").append(departmentOf(product)).append(") = ?");
            }
            return sql.toString();
        }

        /** {@code AND} the receipt {@code invoiceId} holds a matching line. */
        public String invoiceExists(String invoiceId) {
            if (!active()) {
                return "";
            }
            return " AND EXISTS (SELECT 1 FROM sales_invoice_lines xl" + productJoin("xl", "xp")
                    + " WHERE xl.invoice_id = " + invoiceId + lineSql("xl", "xp") + ")";
        }

        /**
         * {@code AND} the restaurant order {@code orderId} holds a matching item; {@code live}
         * leaves voided items out (an order still on the floor).
         */
        public String orderExists(String orderId, boolean live) {
            if (!active()) {
                return "";
            }
            return " AND EXISTS (SELECT 1 FROM restaurant_order_lines xl" + productJoin("xl", "xp")
                    + " WHERE xl.restaurant_order_id = " + orderId
                    + (live ? " AND xl.status <> 'VOIDED'" : "") + lineSql("xl", "xp") + ")";
        }

        /**
         * A LEFT JOIN LATERAL giving {@code m.gross}: the matching lines of receipt {@code i}, at
         * gross in the base currency. Its {@link #params()} come before the WHERE clause's.
         */
        String matchedGrossJoin(int baseScale) {
            return " LEFT JOIN LATERAL (SELECT SUM("
                    + BaseCurrencySql.grossToBase("ml.net_amount", "ml.vat_amount", "i.fx_rate",
                            baseScale)
                    + ") AS gross FROM sales_invoice_lines ml" + productJoin("ml", "mp")
                    + " WHERE ml.invoice_id = i.id" + lineSql("ml", "mp") + ") m ON true";
        }

        /** The product join a department match needs; none for the product filter alone. */
        public String productJoin(String line, String product) {
            return department == null ? ""
                    : " LEFT JOIN products " + product + " ON " + product + ".id = " + line
                            + ".product_id";
        }
    }

    /** What the reports call a line's department: the product's category, or "(no department)". */
    static String departmentOf(String product) {
        return "COALESCE(NULLIF(TRIM(" + product + ".category), ''), '" + NO_DEPARTMENT + "')";
    }

    /** Resolves {@link ItemFilter}; an item not in this company is "not found". */
    public ItemScope itemScope(Long companyId, ItemFilter filter) {
        if (filter == null) {
            return ItemScope.NONE;
        }
        Long productId = null;
        String productName = null;
        if (filter.productUid() != null && !filter.productUid().isBlank()) {
            List<Object[]> found = jdbc.query(
                    "SELECT id, name FROM products WHERE uid = ? AND company_id = ?",
                    (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("name")},
                    filter.productUid().trim(), companyId);
            if (found.isEmpty()) {
                throw NotFoundException.of("Item", filter.productUid());
            }
            productId = (Long) found.get(0)[0];
            productName = (String) found.get(0)[1];
        }
        String department = filter.department() == null || filter.department().isBlank() ? null
                : filter.department().trim();
        return productId == null && department == null ? ItemScope.NONE
                : new ItemScope(productId, productName, department);
    }

    /** The item and department filters as report-header lines; none when not set. */
    public List<String> itemFilterLines(Long companyId, ItemFilter filter) {
        ItemScope scope = itemScope(companyId, filter);
        List<String> lines = new ArrayList<>(2);
        if (scope.productId() != null) {
            lines.add("Item: " + scope.productName());
        }
        if (scope.department() != null) {
            lines.add("Department: " + scope.department());
        }
        return lines;
    }

    private Filter filter(RequestContext.Principal principal, Long companyId, String waiterUid,
                          String branchUid, String branchColumn) {
        Long branchId = null;
        String branchName = null;
        if (branchUid != null && !branchUid.isBlank()) {
            List<Object[]> found = jdbc.query(
                    "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                    (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("name")},
                    branchUid.trim(), companyId);
            if (found.isEmpty()) {
                throw NotFoundException.of("Branch", branchUid);
            }
            branchId = (Long) found.get(0)[0];
            branchName = (String) found.get(0)[1];
        }
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branchId);
        StringBuilder sql = new StringBuilder(scope.sql(branchColumn));
        List<Object> params = new ArrayList<>();
        if (branchId != null) {
            sql.append(" AND ").append(branchColumn).append(" = ?");
            params.add(branchId);
        }
        String waiterName = null;
        if (waiterUid != null && !waiterUid.isBlank()) {
            Object[] waiter = waiter(companyId, waiterUid);
            sql.append(waiterSql(branchColumn));
            params.add(waiter[0]);
            waiterName = (String) waiter[1];
        }
        return new Filter(sql.toString(), params, waiterName, branchName);
    }

    /**
     * {@code {id, display name}} of a waiter of this company: a user assigned to it or to one of
     * its branches, or one who has held one of its orders (so a waiter who has left still reports).
     * Another company's user is "not found", like an unknown uid.
     */
    private Object[] waiter(Long companyId, String waiterUid) {
        List<Object[]> found = jdbc.query("""
                SELECT u.id, u.display_name FROM app_users u
                WHERE u.uid = ?
                  AND (EXISTS (SELECT 1 FROM user_company uc
                                WHERE uc.user_id = u.id AND uc.company_id = ?)
                    OR EXISTS (SELECT 1 FROM user_branch ub JOIN branches b ON b.id = ub.branch_id
                                WHERE ub.user_id = u.id AND b.company_id = ?)
                    OR EXISTS (SELECT 1 FROM restaurant_orders ro
                                WHERE ro.company_id = ?
                                  AND (ro.served_by = u.id OR ro.opened_by = u.id)))
                """,
                (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("display_name")},
                waiterUid.trim(), companyId, companyId, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of("Waiter", waiterUid);
        }
        return found.get(0);
    }

    /**
     * The waiter test for the table {@code branchColumn} belongs to — the waiter being whoever
     * holds the restaurant order: an order ({@code o}) directly; a receipt ({@code i}) through the
     * order it paid; a POS payout ({@code po}) through the receipt it refunded. A receipt or payout
     * with no restaurant order (a counter sale) has no waiter, so it drops out.
     */
    static String waiterSql(String branchColumn) {
        return switch (branchColumn) {
            case "i.branch_id" -> " AND EXISTS (SELECT 1 FROM restaurant_orders wo"
                    + " WHERE wo.sales_invoice_id = i.id"
                    + " AND COALESCE(wo.served_by, wo.opened_by) = ?)";
            case "po.branch_id" -> " AND EXISTS (SELECT 1 FROM sales_invoices wi"
                    + " JOIN restaurant_orders wo ON wo.sales_invoice_id = wi.id"
                    + " WHERE wi.uid = po.source_invoice_uid AND wi.company_id = po.company_id"
                    + " AND COALESCE(wo.served_by, wo.opened_by) = ?)";
            default -> " AND COALESCE(o.served_by, o.opened_by) = ?";
        };
    }

    /** "Waiter: name" for a report header; none when no waiter is chosen. */
    public List<String> waiterLines(Long companyId, String waiterUid) {
        if (waiterUid == null || waiterUid.isBlank()) {
            return List.of();
        }
        return List.of("Waiter: " + waiter(companyId, waiterUid)[1]);
    }

    Header loadHeader(Long companyId) {
        List<Header> found = jdbc.query("""
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone,
                       base_currency
                FROM companies WHERE id = ?
                """,
                (rs, i) -> new Header(rs.getString("name"), rs.getString("legal_name"),
                        rs.getString("tax_id"), rs.getString("vrn"),
                        rs.getString("contact_phone"), rs.getString("contact_email"),
                        rs.getString("address_line1"), rs.getString("address_line2"),
                        rs.getString("city"), rs.getString("region"), rs.getString("country"),
                        rs.getString("time_zone"), rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    static String when(ResultSet rs, String column, ZoneId zone) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.atZoneSameInstant(zone).format(WHEN);
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
