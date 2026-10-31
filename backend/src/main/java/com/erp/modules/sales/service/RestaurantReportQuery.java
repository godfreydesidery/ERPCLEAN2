package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.RestaurantBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillsReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesRowDto;
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
import java.util.List;
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
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid);
        String amount = BaseCurrencySql.toBase("i.gross_total_amount", "i.fx_rate", w.baseScale);
        List<Object> params = new ArrayList<>(List.of(companyId, w.from, w.to));
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
        Window w = window(companyId, fromDate, toDate, waiterUid, branchUid);
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
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        Header header = loadHeader(companyId);
        ZoneId zone = header.zone();
        Filter f = filter(principal, companyId, waiterUid, branchUid);
        List<Object> params = new ArrayList<>(List.of(companyId));
        params.addAll(f.params);
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
                """ + f.sql + """

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

    // ---------------------------------------------------------------- shared

    private record Header(String name, String legalName, String taxId, String vrn,
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

    /** The waiter and branch filters, as SQL on {@code o.} with their parameters. */
    private record Filter(String sql, List<Object> params, String waiterName) {}

    private record Window(Header header, ZoneId zone, OffsetDateTime from, OffsetDateTime to,
                          String filterSql, List<Object> params, String waiterName,
                          int baseScale) {}

    private Window window(Long companyId, LocalDate fromDate, LocalDate toDate, String waiterUid,
                          String branchUid) {
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
        Filter f = filter(principal, companyId, waiterUid, branchUid);
        return new Window(header, zone,
                fromDate.atStartOfDay(zone).toOffsetDateTime(),
                toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                f.sql, f.params, f.waiterName, BaseCurrencySql.baseScale(jdbc, companyId));
    }

    private Filter filter(RequestContext.Principal principal, Long companyId, String waiterUid,
                          String branchUid) {
        Long branchId = null;
        if (branchUid != null && !branchUid.isBlank()) {
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM branches WHERE uid = ? AND company_id = ?",
                    Long.class, branchUid.trim(), companyId);
            if (found.isEmpty()) {
                throw NotFoundException.of("Branch", branchUid);
            }
            branchId = found.get(0);
        }
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branchId);
        StringBuilder sql = new StringBuilder(scope.sql("o.branch_id"));
        List<Object> params = new ArrayList<>();
        if (branchId != null) {
            sql.append(" AND o.branch_id = ?");
            params.add(branchId);
        }
        String waiterName = null;
        if (waiterUid != null && !waiterUid.isBlank()) {
            // Any user of this company's orders; a uid that opened none simply matches nothing.
            List<Object[]> found = jdbc.query(
                    "SELECT id, display_name FROM app_users WHERE uid = ?",
                    (rs, i) -> new Object[] {rs.getLong("id"), rs.getString("display_name")},
                    waiterUid.trim());
            if (found.isEmpty()) {
                throw NotFoundException.of("Waiter", waiterUid);
            }
            sql.append(" AND COALESCE(o.served_by, o.opened_by) = ?");
            params.add(found.get(0)[0]);
            waiterName = (String) found.get(0)[1];
        }
        return new Filter(sql.toString(), params, waiterName);
    }

    private Header loadHeader(Long companyId) {
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

    private static String when(ResultSet rs, String column, ZoneId zone) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.atZoneSameInstant(zone).format(WHEN);
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
