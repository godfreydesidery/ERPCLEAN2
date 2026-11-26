package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.ZReadBreakdownDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only sales sections of a Z-read (summary, categories, departments, team members) for one POS
 * session. Writes nothing.
 *
 * <p>Basis = the Z-read's own {@code totalSalesAmount}: the session's FINALISED invoice lines, less
 * the POS item returns recorded against the session (ADR-0072), in the invoice's face currency — so
 * the net figure here and every grouped list sum back to the total the Z-read already prints.
 * A line's {@code net_amount + vat_amount} is what was actually charged for it, a whole-bill
 * discount already spread over the lines, so the groups add up without any extra allocation.
 */
@Component
@Transactional(readOnly = true)
public class ZReadBreakdownQuery {

    private final JdbcTemplate jdbc;

    public ZReadBreakdownQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sold lines (+) and returned lines (−) of the session, each with its grouping keys. */
    private static final String LINES = """
            WITH lines AS (
                SELECT i.id AS invoice_id, 1 AS sold,
                       l.net_amount + l.vat_amount AS charged,
                       l.vat_amount AS vat,
            """ + "           " + BaseCurrencySql.lineDiscountFace() + " AS discount,\n" + """
                       p.category AS category,
                       st.name AS station,
                       COALESCE(sv.display_name, cr.display_name) AS member
                FROM sales_invoices i
                JOIN sales_invoice_lines l ON l.invoice_id = i.id
                LEFT JOIN products p ON p.id = l.product_id
                LEFT JOIN restaurant_orders ro ON ro.sales_invoice_id = i.id
                LEFT JOIN LATERAL (
                    SELECT s.name FROM restaurant_order_lines rol
                    JOIN prep_stations s ON s.id = rol.prep_station_id
                    WHERE rol.restaurant_order_id = ro.id AND rol.product_id = l.product_id
                    ORDER BY rol.id LIMIT 1) st ON TRUE
                LEFT JOIN app_users sv ON sv.id = COALESCE(ro.served_by, ro.opened_by)
                LEFT JOIN app_users cr ON cr.id = i.created_by
                WHERE i.company_id = ? AND i.pos_session_id = ? AND i.status = 'FINALISED'
                UNION ALL
                SELECT i.id, 0,
                       -(rl.net_amount + rl.vat_amount),
                       -rl.vat_amount,
            """ + "           -ROUND(" + BaseCurrencySql.lineDiscountFace()
            + " * rl.qty_returned / l.quantity, " + BaseCurrencySql.INVOICE_SCALE_SQL + "),\n"
            + """
                       p.category,
                       st.name,
                       COALESCE(sv.display_name, cr.display_name)
            """ + PosReturnSql.RETURNED_FROM + """
                LEFT JOIN products p ON p.id = l.product_id
                LEFT JOIN restaurant_orders ro ON ro.sales_invoice_id = i.id
                LEFT JOIN LATERAL (
                    SELECT s.name FROM restaurant_order_lines rol
                    JOIN prep_stations s ON s.id = rol.prep_station_id
                    WHERE rol.restaurant_order_id = ro.id AND rol.product_id = l.product_id
                    ORDER BY rol.id LIMIT 1) st ON TRUE
                LEFT JOIN app_users sv ON sv.id = COALESCE(ro.served_by, ro.opened_by)
                LEFT JOIN app_users cr ON cr.id = i.created_by
                WHERE rt.company_id = ? AND rt.pos_session_id = ?
            )
            """;

    public ZReadBreakdownDto forSession(Long companyId, Long sessionId) {
        Object[] args = {companyId, sessionId, companyId, sessionId};

        BigDecimal[] totals = new BigDecimal[4];
        long[] count = new long[1];
        jdbc.query(LINES + """
                SELECT COALESCE(SUM(charged + discount), 0) AS gross,
                       COALESCE(SUM(discount), 0)           AS discount,
                       COALESCE(SUM(charged), 0)            AS net,
                       COALESCE(SUM(vat), 0)                AS vat,
                       COUNT(DISTINCT invoice_id) FILTER (WHERE sold = 1) AS sales
                FROM lines
                """, rs -> {
            totals[0] = rs.getBigDecimal("gross");
            totals[1] = rs.getBigDecimal("discount");
            totals[2] = rs.getBigDecimal("net");
            totals[3] = rs.getBigDecimal("vat");
            count[0] = rs.getLong("sales");
        }, args);

        return new ZReadBreakdownDto(totals[0], totals[1], totals[2], totals[3], count[0],
                grouped("COALESCE(NULLIF(TRIM(category), ''), 'Uncategorised')", args),
                grouped("COALESCE(station, 'Unassigned')", args),
                grouped("COALESCE(member, 'Unknown')", args));
    }

    /** Net sales and number of sales per {@code keyExpr}, largest first; empty groups dropped. */
    private List<ZReadBreakdownDto.Line> grouped(String keyExpr, Object[] args) {
        List<ZReadBreakdownDto.Line> out = new ArrayList<>();
        jdbc.query(LINES + "SELECT " + keyExpr + " AS name, SUM(charged) AS amount,\n"
                        + "       COUNT(DISTINCT invoice_id) FILTER (WHERE sold = 1) AS sales\n"
                        + "FROM lines GROUP BY 1 HAVING SUM(charged) <> 0\n"
                        + "ORDER BY 2 DESC, 1",
                rs -> {
                    out.add(new ZReadBreakdownDto.Line(rs.getString("name"),
                            rs.getBigDecimal("amount"), rs.getLong("sales")));
                }, args);
        return out;
    }
}
