package com.erp.modules.sales.service;

/**
 * SQL fragments shared by the sales reports that net POS item returns (ADR-0072).
 *
 * <p>A POS item return leaves the original invoice FINALISED, so a report that sums invoice lines
 * still counts a returned item as sold. Each report therefore adds the returned lines back as
 * negatives, <b>dated by the return's {@code created_at}</b> (the day the cash left the till, which
 * is the date the GL journal, the VAT return and the till dashboard use), converted to the base
 * currency at the <b>originating invoice's stamped rate</b> exactly as the invoice's own lines are.
 *
 * <p>Fixed aliases: {@code rl} return line, {@code rt} return header, {@code i} the original
 * invoice, {@code l} the sold line. Every fragment is a literal; nothing a caller types reaches it.
 */
final class PosReturnSql {

    /** Returned lines with their return header, the original invoice and the sold line. */
    static final String RETURNED_FROM = """
            FROM pos_sale_return_lines rl
            JOIN pos_sale_returns rt ON rt.id = rl.pos_sale_return_id
            JOIN sales_invoices i ON i.id = rt.sales_invoice_id
            JOIN sales_invoice_lines l ON l.id = rl.sales_invoice_line_id
            """;

    /**
     * Cost reversal: the SALE_REVERSAL movements {@code PosSaleReturnStockHandler} posts for a
     * return (source document = the return's uid), with the return and its original invoice.
     */
    static final String COST_REVERSAL_FROM = """
            FROM stock_movements sm
            JOIN pos_sale_returns rt ON rt.uid = sm.source_document_uid
                                    AND rt.company_id = sm.company_id
            JOIN sales_invoices i ON i.id = rt.sales_invoice_id
            """;

    /** The predicate that picks those movements out of the ledger (the stock module's constants). */
    static final String COST_REVERSAL_WHERE =
            "sm.movement_type = 'SALE_REVERSAL' AND sm.source_document_type = 'POS_SALE_RETURN'";

    private PosReturnSql() {}

    static String net(int baseScale) {
        return BaseCurrencySql.toBase("rl.net_amount", "i.fx_rate", baseScale);
    }

    static String vat(int baseScale) {
        return BaseCurrencySql.toBase("rl.vat_amount", "i.fx_rate", baseScale);
    }

    /** Base gross of the returned line = base net + base VAT, as on the sold line. */
    static String gross(int baseScale) {
        return BaseCurrencySql.grossToBase("rl.net_amount", "rl.vat_amount", "i.fx_rate", baseScale);
    }

    /**
     * The returned share of everything taken off the sold line (line amount, line %, document
     * share — {@link BaseCurrencySql#lineDiscountFace()}), by returned / sold quantity, in base.
     * Pro-rated from the same expression the sold side uses so a fully returned line nets to zero.
     */
    static String discount(int baseScale) {
        return BaseCurrencySql.toBase("ROUND(" + BaseCurrencySql.lineDiscountFace()
                + " * rl.qty_returned / l.quantity, " + BaseCurrencySql.INVOICE_SCALE_SQL + ")",
                "i.fx_rate", baseScale);
    }
}
