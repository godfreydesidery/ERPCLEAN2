package com.erp.modules.sales.service;

import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyMinorUnits;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL fragments that turn a sales document's face amounts into the company's BASE currency for the
 * sales reports (Sales Report, Sales Summary, Profitability).
 *
 * <p><b>Why.</b> Every monetary column on {@code sales_invoices} / {@code sales_invoice_lines} is in
 * the DOCUMENT currency (BR-SALES-04). Summing them straight into a report headed in the base
 * currency added a USD 14 sale as TZS 14. The rate to use is the one stamped on the invoice header
 * at finalise ({@code sales_invoices.fx_rate}, ADR-0036 D-4: units of base per 1 document unit,
 * immutable after finalise) — the same rate the GL posting applied, so the report and the ledger
 * agree. Cost of sales ({@code stock_movements.value_amount}) is already base and is not touched.
 *
 * <p><b>Rounding.</b> Each LINE amount is converted and rounded HALF_UP to the base currency's minor
 * units on its own, and only then summed. That makes every grouping additive: the per-customer,
 * per-day, per-branch and per-product views add up to exactly the same total, because they all add
 * the same rounded line values. A base gross is built as base net + base VAT (never gross × rate) so
 * {@code net + VAT = gross} holds in base as it does on the document, mirroring the GL, whose
 * debit leg is the plug of its converted net and VAT legs.
 *
 * <p>A base-currency document ({@code fx_rate = 1}) is passed through untouched, so a single-currency
 * company's figures are byte-identical to before.
 */
final class BaseCurrencySql {

    private BaseCurrencySql() {}

    /**
     * {@code faceExpr} converted to base at {@code rateExpr}, rounded to {@code baseScale}. Both
     * expressions are fixed SQL text chosen by the calling query; {@code baseScale} is an int read
     * from the currencies master — nothing a caller types reaches the SQL.
     *
     * @param faceExpr must not be NULL-able (wrap a nullable column in COALESCE first)
     */
    static String toBase(String faceExpr, String rateExpr, int baseScale) {
        return "(CASE WHEN " + rateExpr + " = 1 THEN " + faceExpr
                + " ELSE CAST(ROUND(" + faceExpr + " * " + rateExpr + ", " + baseScale
                + ") AS NUMERIC(19,4)) END)";
    }

    /** Base gross of an invoice line = base net + base VAT (see the class javadoc). */
    static String grossToBase(String netExpr, String vatExpr, String rateExpr, int baseScale) {
        return "(" + toBase(netExpr, rateExpr, baseScale) + " + "
                + toBase(vatExpr, rateExpr, baseScale) + ")";
    }

    /**
     * Minor units of the company's base currency, from the {@code currencies} master (fallback to
     * the static table for a code the master does not list).
     */
    static int baseScale(JdbcTemplate jdbc, Long companyId) {
        List<int[]> found = jdbc.query(
                """
                SELECT c.base_currency AS base_currency, cur.minor_units AS minor_units
                FROM companies c
                LEFT JOIN currencies cur ON cur.code = c.base_currency
                WHERE c.id = ?
                """,
                (rs, rowNum) -> {
                    int mu = rs.getInt("minor_units");
                    return new int[]{rs.wasNull()
                            ? CurrencyMinorUnits.fallback(rs.getString("base_currency")) : mu};
                },
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0)[0];
    }
}
