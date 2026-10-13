package com.erp.support;

import com.erp.platform.common.domain.Ulid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL fixtures for report integration tests.
 *
 * <p>Report queries read finished business data — finalised invoices, tenders, stock movements on
 * chosen DATES. Producing that through the services means a fiscal calendar, a GL, an outbox
 * dispatch per sale and no control over {@code occurred_at}, which is the one thing an ageing or a
 * per-day report has to be tested on. These inserts write exactly the rows the queries read, with
 * every NOT NULL column the schema demands, and nothing else. Plain helper — not a Spring bean.
 */
public final class ReportSeed {

    private final JdbcTemplate jdbc;

    public ReportSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long unit(long companyId, String code) {
        return id("INSERT INTO units_of_measure (uid, company_id, code, name) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Ulid.next(), companyId, code, code);
    }

    public long supplier(long companyId, String code, String name) {
        return id("INSERT INTO suppliers (uid, company_id, code, party_type, display_name, "
                + "supplier_kind) VALUES (?, ?, ?, 'BUSINESS', ?, 'GOODS') RETURNING id",
                Ulid.next(), companyId, code, name);
    }

    public long product(long companyId, long unitId, String code, String name, Long supplierId) {
        return id("INSERT INTO products (uid, company_id, code, name, type, base_unit_id, "
                + "preferred_supplier_id) VALUES (?, ?, ?, ?, 'GOODS', ?, ?) RETURNING id",
                Ulid.next(), companyId, code, name, unitId, supplierId);
    }

    public String uidOf(String table, long id) {
        return jdbc.queryForObject("SELECT uid FROM " + table + " WHERE id = ?", String.class, id);
    }

    public long customer(long companyId, String code, String name) {
        return id("INSERT INTO customers (uid, company_id, code, party_type, display_name, "
                + "customer_kind) VALUES (?, ?, ?, 'INDIVIDUAL', ?, 'CASH_WALK_IN') RETURNING id",
                Ulid.next(), companyId, code, name);
    }

    public long agent(long companyId, String code, String name) {
        return id("INSERT INTO agents (uid, company_id, code, party_type, display_name, "
                + "agent_kind) VALUES (?, ?, ?, 'INDIVIDUAL', ?, 'EXTERNAL') RETURNING id",
                Ulid.next(), companyId, code, name);
    }

    public long route(long companyId, String code, String name) {
        return id("INSERT INTO routes (uid, company_id, code, name) VALUES (?, ?, ?, ?) "
                + "RETURNING id", Ulid.next(), companyId, code, name);
    }

    public long location(long companyId, long branchId, String code) {
        return id("INSERT INTO stock_locations (uid, company_id, branch_id, code, name, "
                + "location_type) VALUES (?, ?, ?, ?, ?, 'WAREHOUSE') RETURNING id",
                Ulid.next(), companyId, branchId, code, code);
    }

    /** A sales invoice header. Totals are not read by the reports, so they stay at zero. */
    public Invoice invoice(long companyId, long branchId, long customerId, long agentId,
                           Long routeId, Long createdBy, String status,
                           OffsetDateTime finalisedAt) {
        return invoice(companyId, branchId, customerId, agentId, routeId, createdBy, status,
                finalisedAt, "TZS", BigDecimal.ONE);
    }

    /**
     * A sales invoice header in {@code currency}, stamped with {@code fxRate} (units of base per 1
     * document unit) as finalise would stamp it. Lines added with {@link #line(Invoice, int, long,
     * long, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal)} carry the same currency.
     */
    public Invoice invoice(long companyId, long branchId, long customerId, long agentId,
                           Long routeId, Long createdBy, String status,
                           OffsetDateTime finalisedAt, String currency, BigDecimal fxRate) {
        String uid = Ulid.next();
        long id = id("INSERT INTO sales_invoices (uid, company_id, branch_id, invoice_number, "
                + "status, customer_id, agent_id, currency, route_id, created_by, finalised_at, "
                + "fx_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                // A draft carries no number (chk_sales_invoice_number_when_finalised).
                uid, companyId, branchId, "DRAFT".equals(status) ? null : "INV-" + uid.substring(16),
                status, customerId,
                agentId, currency, routeId, createdBy, finalisedAt, fxRate);
        return new Invoice(id, uid, companyId, branchId, currency);
    }

    public void line(Invoice inv, int lineNo, long productId, long unitId, BigDecimal qty,
                     BigDecimal qtyInBase, BigDecimal net, BigDecimal vat, BigDecimal discount) {
        // An EXCLUSIVE line consistent with InvoiceTotalsCalculator: unit price × qty less the
        // (exclusive) line discount is the net, VAT is 18% of it. The reports derive the discount
        // from these columns (RPT-16), so they must agree with net/vat as a real line's would.
        BigDecimal unitPrice = net.add(discount).divide(qty, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal vatRate = vat.signum() > 0 ? new BigDecimal("0.18") : BigDecimal.ZERO;
        jdbc.update("INSERT INTO sales_invoice_lines (uid, invoice_id, company_id, branch_id, "
                + "line_no, product_id, product_code, product_name, unit_id, unit_name, quantity, "
                + "qty_in_base, list_price_amount, unit_price_amount, vat_status, vat_rate, "
                + "net_amount, vat_amount, gross_amount, line_discount_amount, currency) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'P', 'P', ?, 'PCS', ?, ?, ?, ?, 'STANDARD', ?, "
                + "?, ?, ?, ?, ?)",
                Ulid.next(), inv.id(), inv.companyId(), inv.branchId(), lineNo, productId,
                unitId, qty, qtyInBase, unitPrice, unitPrice, vatRate, net, vat, net.add(vat),
                discount, inv.currency());
    }

    public void payment(Invoice inv, String tender, BigDecimal amount, BigDecimal change,
                        String currency, OffsetDateTime receivedAt, long receivedBy) {
        jdbc.update("INSERT INTO sales_invoice_payments (uid, invoice_id, company_id, branch_id, "
                + "tender_type, amount, currency, change_amount, received_at, received_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(), inv.id(), inv.companyId(), inv.branchId(), tender, amount, currency,
                change, receivedAt, receivedBy);
    }

    /** A stock movement. {@code valueAmount} null with {@code unitCost} null = an uncosted one. */
    public void movement(long companyId, long branchId, long locationId, long productId,
                         String type, BigDecimal qty, OffsetDateTime occurredAt,
                         BigDecimal unitCost, BigDecimal valueAmount, String sourceDocumentUid) {
        jdbc.update("INSERT INTO stock_movements (uid, company_id, branch_id, location_id, "
                + "product_id, movement_type, quantity, occurred_at, unit_cost_amount, "
                + "value_amount, source_document_uid, reason_code) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(), companyId, branchId, locationId, productId, type, qty, occurredAt,
                unitCost, valueAmount, sourceDocumentUid,
                "ADJUSTMENT".equals(type) ? "COUNT_CORRECTION" : null);
    }

    public long onHand(long companyId, long branchId, long locationId, long productId,
                       BigDecimal qty, BigDecimal value, BigDecimal avgCost,
                       BigDecimal reorderLevel, BigDecimal maxQty) {
        return id("INSERT INTO stock_on_hand (uid, company_id, branch_id, location_id, "
                + "product_id, quantity, on_hand_value, avg_cost, reorder_level, max_qty) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Ulid.next(), companyId, branchId, locationId, productId, qty,
                value != null ? value : BigDecimal.ZERO, avgCost, reorderLevel, maxQty);
    }

    private long id(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("insert returned no id");
        }
        return id;
    }

    public record Invoice(long id, String uid, long companyId, long branchId, String currency) {}
}
