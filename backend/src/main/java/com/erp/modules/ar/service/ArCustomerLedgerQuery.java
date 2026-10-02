package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArCustomerLedgerDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerRowDto;
import com.erp.modules.ar.domain.dto.ArCustomerRefDto;
import com.erp.modules.ar.domain.enums.ArLedgerEntryType;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer statement over a period — balance brought forward, every movement with a running
 * balance, closing balance — read straight from the AR sub-ledger tables.
 *
 * <p>The movements, and why each one is on the side it is:
 * <ul>
 *   <li><b>Invoice / opening balance</b> ({@code ar_invoices}, every status — a PAID or WRITTEN_OFF
 *       item was still billed): debit {@code original_amount} on {@code invoice_date}.</li>
 *   <li><b>Receipt</b> ({@code ar_receipts}): credit the full {@code amount} on
 *       {@code receipt_date}, allocated or not — an on-account receipt reduces what the customer
 *       owes just as much.</li>
 *   <li><b>Receipt reversal</b> (a bounced cheque stamps {@code reversed_at} on the receipt; no
 *       second row is written): debit the same amount on the reversal date, in the company's time
 *       zone. Showing both lines is what the customer needs to see — "you paid, it bounced".</li>
 *   <li><b>Credit note</b> ({@code ar_credit_notes}): credit {@code amount} on {@code note_date},
 *       applied or not.</li>
 *   <li><b>Write-off</b> ({@code ar_write_offs}): credit {@code amount} on {@code write_off_date}.</li>
 * </ul>
 * So the closing balance is Σ billed − Σ received + Σ reversed − Σ credited − Σ written off: the
 * same figure as outstanding − unallocated receipts − unapplied credit notes that the balance and
 * reconciliation reads use, built from the movements instead of the open-item residue.
 *
 * <p>Settlement discounts recorded on a receipt allocation are data-only — they never reduce the
 * invoice outstanding — so they are not movements here either.
 *
 * <p>One currency per statement (default: the company's base currency). Movements in other
 * currencies are counted and left off rather than summed into a balance they do not belong to.
 *
 * <p>Cross-module reads (customers, companies) are scalar native SQL — no parties/iam entity or
 * repository import (module boundary rule).
 */
@Component
@Transactional(readOnly = true)
public class ArCustomerLedgerQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";

    /**
     * Every movement for one customer of one company, as (entry_date, sort_key, src_id, entry_type,
     * reference, description, currency, debit, credit). sort_key orders same-day movements so a
     * day's invoices print before that day's receipts.
     */
    private static final String ENTRIES_CTE = """
            WITH entries AS (
                SELECT i.invoice_date AS entry_date,
                       1              AS sort_key,
                       i.id           AS src_id,
                       CASE WHEN i.source = 'OPENING_BALANCE' THEN 'OPENING_BALANCE'
                            ELSE 'INVOICE' END AS entry_type,
                       i.document_no  AS reference,
                       CASE WHEN i.source = 'OPENING_BALANCE' THEN 'Opening balance'
                            ELSE 'Invoice, due ' || to_char(i.due_date, 'DD-Mon-YYYY') END AS description,
                       i.currency     AS currency,
                       i.original_amount AS debit,
                       CAST(0 AS NUMERIC) AS credit
                FROM ar_invoices i
                WHERE i.company_id = :companyId AND i.customer_id = :customerId
                UNION ALL
                SELECT r.receipt_date, 2, r.id, 'RECEIPT', r.receipt_number,
                       'Payment received (' || replace(r.tender_type, '_', ' ') || ')'
                           || COALESCE(', ref ' || NULLIF(r.bank_reference, ''), ''),
                       r.currency, CAST(0 AS NUMERIC), r.amount
                FROM ar_receipts r
                WHERE r.company_id = :companyId AND r.customer_id = :customerId
                UNION ALL
                SELECT CAST((r.reversed_at AT TIME ZONE :tz) AS DATE), 3, r.id, 'RECEIPT_REVERSAL',
                       r.receipt_number, 'Payment reversed (cheque returned unpaid)',
                       r.currency, r.amount, CAST(0 AS NUMERIC)
                FROM ar_receipts r
                WHERE r.company_id = :companyId AND r.customer_id = :customerId
                  AND r.reversed_at IS NOT NULL
                UNION ALL
                SELECT c.note_date, 4, c.id, 'CREDIT_NOTE', c.credit_note_number,
                       'Credit note: ' || c.reason,
                       c.currency, CAST(0 AS NUMERIC), c.amount
                FROM ar_credit_notes c
                WHERE c.company_id = :companyId AND c.customer_id = :customerId
                UNION ALL
                SELECT w.write_off_date, 5, w.id, 'WRITE_OFF', inv.document_no,
                       'Written off: ' || w.reason,
                       w.currency, CAST(0 AS NUMERIC), w.amount
                FROM ar_write_offs w
                LEFT JOIN ar_invoices inv ON inv.id = w.ar_invoice_id
                WHERE w.company_id = :companyId AND w.customer_id = :customerId
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopeGuard                 scopeGuard;

    public ArCustomerLedgerQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = new NamedParameterJdbcTemplate(jdbc);
        this.scopeGuard = scopeGuard;
    }

    /**
     * Resolves the customer a statement read names — by uid (what the screen sends) or by id (what
     * the original endpoint took) — INSIDE {@code companyId}. A uid or id from another company is
     * "not found", never a silent widen.
     */
    public ArCustomerRefDto resolveCustomer(Long companyId, Long customerId, String customerUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        boolean byUid = customerUid != null && !customerUid.isBlank();
        if (!byUid && customerId == null) {
            throw new IllegalArgumentException("Choose a customer.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("companyId", companyId);
        String where;
        if (byUid) {
            where = "uid = :uid";
            p.addValue("uid", customerUid.trim());
        } else {
            where = "id = :id";
            p.addValue("id", customerId);
        }
        List<ArCustomerRefDto> found = jdbc.query(
                "SELECT id, uid, code, display_name, tin, vrn FROM customers"
                        + " WHERE company_id = :companyId AND " + where,
                p,
                (rs, n) -> new ArCustomerRefDto(rs.getLong("id"), rs.getString("uid"),
                        rs.getString("code"), rs.getString("display_name"),
                        rs.getString("tin"), rs.getString("vrn")));
        if (found.isEmpty()) {
            throw new NotFoundException("Customer not found.");
        }
        return found.get(0);
    }

    /**
     * The statement for one customer.
     *
     * @param fromDate first day shown; null = from the customer's first movement (no b/f balance)
     * @param toDate   last day shown (inclusive); null = today
     * @param currency the statement currency; null/blank = the company's base currency
     */
    public ArCustomerLedgerDto ledger(Long companyId, Long customerId, String customerUid,
                                      LocalDate fromDate, LocalDate toDate, String currency) {
        ArCustomerRefDto customer = resolveCustomer(companyId, customerId, customerUid);
        CompanyRow co = loadCompany(companyId);

        LocalDate to = toDate != null ? toDate : LocalDate.now();
        if (fromDate != null && fromDate.isAfter(to)) {
            throw new IllegalArgumentException("The start date must be on or before the end date.");
        }
        String ccy = currency != null && !currency.isBlank()
                ? currency.trim().toUpperCase() : co.baseCurrency();

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("customerId", customer.id())
                .addValue("tz", co.timeZone())
                .addValue("currency", ccy)
                .addValue("toDate", to)
                .addValue("fromDate", fromDate);

        BigDecimal opening = BigDecimal.ZERO;
        if (fromDate != null) {
            opening = jdbc.queryForObject(ENTRIES_CTE + """
                    SELECT COALESCE(SUM(debit - credit), 0)
                    FROM entries
                    WHERE currency = :currency AND entry_date < :fromDate
                    """, p, BigDecimal.class);
        }

        String periodPredicate = fromDate != null ? " AND entry_date >= :fromDate" : "";
        List<Object[]> raw = jdbc.query(ENTRIES_CTE + """
                SELECT entry_date, entry_type, reference, description, debit, credit
                FROM entries
                WHERE currency = :currency AND entry_date <= :toDate
                """ + periodPredicate + """

                ORDER BY entry_date, sort_key, reference NULLS LAST, src_id
                """, p,
                (rs, n) -> new Object[] {
                        rs.getObject("entry_date", LocalDate.class),
                        rs.getString("entry_type"),
                        rs.getString("reference"),
                        rs.getString("description"),
                        rs.getBigDecimal("debit"),
                        rs.getBigDecimal("credit")});

        Integer others = jdbc.queryForObject(ENTRIES_CTE + """
                SELECT COUNT(*) FROM entries
                WHERE currency <> :currency AND entry_date <= :toDate
                """, p, Integer.class);

        BigDecimal running = opening;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<ArCustomerLedgerRowDto> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            BigDecimal debit  = (BigDecimal) r[4];
            BigDecimal credit = (BigDecimal) r[5];
            running     = running.add(debit).subtract(credit);
            totalDebit  = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
            rows.add(new ArCustomerLedgerRowDto((LocalDate) r[0],
                    ArLedgerEntryType.valueOf((String) r[1]),
                    (String) r[2], (String) r[3], debit, credit, running));
        }

        return new ArCustomerLedgerDto(co.header(), customer.uid(), customer.code(), customer.name(),
                customer.tin(), customer.vrn(), fromDate, to, ccy, opening, rows,
                totalDebit, totalCredit, running, others != null ? others : 0,
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private record CompanyRow(ReportCompanyHeaderDto header, String timeZone, String baseCurrency) {}

    private CompanyRow loadCompany(Long companyId) {
        List<CompanyRow> found = jdbc.query("""
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
                FROM companies WHERE id = :companyId
                """,
                new MapSqlParameterSource("companyId", companyId),
                (rs, n) -> new CompanyRow(
                        new ReportCompanyHeaderDto(
                                rs.getString("name"), rs.getString("legal_name"),
                                rs.getString("address_line1"), rs.getString("address_line2"),
                                rs.getString("city"), rs.getString("region"), rs.getString("country"),
                                rs.getString("contact_phone"), rs.getString("contact_email"),
                                rs.getString("tax_id"), rs.getString("vrn")),
                        rs.getString("time_zone") != null ? rs.getString("time_zone") : DEFAULT_TIME_ZONE,
                        rs.getString("base_currency") != null ? rs.getString("base_currency") : "TZS"));
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }
}
