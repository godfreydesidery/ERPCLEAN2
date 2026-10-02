package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Splits the AR sub-ledger into a reliable BASE-currency total and per-currency amounts that have
 * no reliable base value (owner ruling 2026-10-02: "per currency, convert only reliable rows").
 *
 * <p>The three sub-ledger terms are the ones {@link ArBalanceServiceImpl} and
 * {@link ArReconciliationQuery} have always netted: open items (OPEN/PARTIAL outstanding) − on-account
 * receipts (unallocated, not reversed) − unapplied credit notes.
 *
 * <p><b>Reliability.</b> A row in the company base currency is always reliable (rate 1, base =
 * face). A FOREIGN-currency row is reliable only when its stored {@code fx_rate <> 1}: migration
 * V62 back-filled {@code fx_rate = 1} and {@code base_* = face} onto every pre-FX row regardless
 * of currency, and a genuine 1:1 rate against the base currency is implausible, so
 * {@code currency <> base AND fx_rate = 1} is read as that fill. A reliable foreign row counts at
 * its STORED base value (the value AR control was posted at): {@code base_outstanding_amount} for
 * an open item, {@code unallocated × fx_rate} for a receipt (the rate its unallocated cash leg was
 * posted at), {@code base_unapplied_amount} for a credit note — each falling back to
 * {@code face × fx_rate} when the base column is empty.
 *
 * <p>Scalar native SQL, read-only.
 */
@Component
@Transactional(readOnly = true)
public class ArFxSplitQuery {

    /** Reliable base total + per-currency unreliable foreign amounts. */
    public record Split(BigDecimal baseTotal, List<ArUnconvertedAmountDto> unconverted) {}

    private final JdbcTemplate jdbc;

    public ArFxSplitQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param companyId  tenant company (caller has already passed its tenant check)
     * @param customerId one customer, or {@code null} for the whole company
     */
    public Split split(Long companyId, Long customerId) {
        String byCustomer = customerId != null ? " AND x.customer_id = ?" : "";
        String sql = """
                WITH co AS (
                    SELECT c.base_currency AS base,
                           COALESCE((SELECT cur.minor_units FROM currencies cur
                                     WHERE cur.code = c.base_currency), 2) AS mu
                    FROM companies c WHERE c.id = ?
                ), t AS (
                    SELECT x.currency, x.fx_rate,
                           x.outstanding_amount AS face,
                           COALESCE(x.base_outstanding_amount,
                                    ROUND(x.outstanding_amount * x.fx_rate, co.mu)) AS stored_base
                    FROM ar_invoices x, co
                    WHERE x.company_id = ? AND x.status IN ('OPEN','PARTIAL')%1$s
                    UNION ALL
                    SELECT x.currency, x.fx_rate,
                           -COALESCE(x.unallocated_amount, 0),
                           -ROUND(COALESCE(x.unallocated_amount, 0) * x.fx_rate, co.mu)
                    FROM ar_receipts x, co
                    WHERE x.company_id = ? AND x.reversed_at IS NULL
                      AND COALESCE(x.unallocated_amount, 0) <> 0%1$s
                    UNION ALL
                    SELECT x.currency, x.fx_rate,
                           -x.unapplied_amount,
                           -COALESCE(x.base_unapplied_amount,
                                     ROUND(x.unapplied_amount * x.fx_rate, co.mu))
                    FROM ar_credit_notes x, co
                    WHERE x.company_id = ? AND x.unapplied_amount <> 0%1$s
                )
                SELECT t.currency,
                       (t.currency = co.base OR t.fx_rate <> 1) AS reliable,
                       SUM(CASE WHEN t.currency = co.base THEN t.face ELSE t.stored_base END)
                           AS base_amount,
                       SUM(t.face) AS face_amount,
                       COUNT(*) AS items
                FROM t, co
                GROUP BY t.currency, (t.currency = co.base OR t.fx_rate <> 1)
                """.formatted(byCustomer);

        List<Object> args = new ArrayList<>();
        args.add(companyId);
        for (int i = 0; i < 3; i++) {
            args.add(companyId);
            if (customerId != null) {
                args.add(customerId);
            }
        }

        BigDecimal[] base = {BigDecimal.ZERO};
        Map<String, BigDecimal> foreign = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        jdbc.query(sql, rs -> {
            String currency = rs.getString("currency");
            if (rs.getBoolean("reliable")) {
                base[0] = base[0].add(nz(rs.getBigDecimal("base_amount")));
            } else {
                foreign.merge(currency, nz(rs.getBigDecimal("face_amount")), BigDecimal::add);
                counts.merge(currency, rs.getInt("items"), Integer::sum);
            }
        }, args.toArray());

        List<ArUnconvertedAmountDto> unconverted = new ArrayList<>();
        foreign.forEach((cur, amt) -> unconverted.add(
                new ArUnconvertedAmountDto(cur, amt, counts.getOrDefault(cur, 0))));
        return new Split(base[0], unconverted);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
