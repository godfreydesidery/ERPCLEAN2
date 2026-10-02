package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApSupplierLedgerDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerRowDto;
import com.erp.modules.ap.domain.dto.ApSupplierRefDto;
import com.erp.modules.ap.domain.enums.ApLedgerEntryType;
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
 * Supplier statement over a period — balance brought forward, every movement with a running
 * balance, closing balance — read straight from the AP sub-ledger tables. There was no per-supplier
 * transaction list before this; the screen showed only open bills and the balance.
 *
 * <p>The movements, and why each one is on the side it is:
 * <ul>
 *   <li><b>Bill / opening balance</b> ({@code supplier_bills}): credit {@code gross_amount} on
 *       {@code bill_date} — but only once the bill is ON the ledger, i.e. MATCHED, APPROVED,
 *       PARTIALLY_PAID or PAID. A DRAFT bill has posted nothing, and a HELD bill (price/qty variance
 *       not yet accepted) has not been posted to the AP control account either; both are what the
 *       balance and reconciliation reads leave out too.</li>
 *   <li><b>Payment</b> ({@code ap_payments}): debit the full {@code amount} on
 *       {@code payment_date} — the amount relieved from the bills, WHT included (the WHT is paid to
 *       TRA on the supplier's behalf; the description says how much). A payment-run payment that
 *       spans several suppliers carries no {@code supplier_id}; its share for THIS supplier is the
 *       sum of its allocations to this supplier's bills.</li>
 *   <li><b>Payment reversal</b> (a bounced cheque stamps {@code reversed_at}; no second row is
 *       written): credit the same amount on the reversal date, in the company's time zone.</li>
 *   <li><b>Debit note</b> ({@code ap_debit_notes}): debit {@code amount} on {@code note_date},
 *       applied or not.</li>
 * </ul>
 *
 * <p>Settlement discounts / write-offs recorded on a payment allocation are data-only — they never
 * reduce the bill outstanding — so they are not movements here either.
 *
 * <p>One currency per statement (default: the company's base currency). Movements in other
 * currencies are counted and left off. Cross-module reads (suppliers, companies) are scalar native
 * SQL — no parties/iam entity or repository import.
 */
@Component
@Transactional(readOnly = true)
public class ApSupplierLedgerQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";

    private static final String ENTRIES_CTE = """
            WITH run_shares AS (
                -- a payment with no supplier on its header: this supplier's share of it
                SELECT p.id, p.payment_date, p.payment_number, p.tender_type, p.bank_reference,
                       p.currency, p.reversed_at, SUM(a.allocated_amount) AS share
                FROM ap_payments p
                JOIN ap_payment_allocations a ON a.ap_payment_id = p.id
                JOIN supplier_bills b         ON b.id = a.supplier_bill_id
                WHERE p.company_id = :companyId AND p.supplier_id IS NULL
                  AND b.supplier_id = :supplierId
                GROUP BY p.id, p.payment_date, p.payment_number, p.tender_type, p.bank_reference,
                         p.currency, p.reversed_at
            ),
            entries AS (
                SELECT b.bill_date AS entry_date,
                       1           AS sort_key,
                       b.id        AS src_id,
                       CASE WHEN b.source = 'OPENING_BALANCE' THEN 'OPENING_BALANCE'
                            ELSE 'BILL' END AS entry_type,
                       b.bill_number AS reference,
                       CASE WHEN b.source = 'OPENING_BALANCE' THEN 'Opening balance'
                            ELSE 'Bill, supplier invoice ' || b.supplier_invoice_no
                                 || ', due ' || to_char(b.due_date, 'DD-Mon-YYYY') END AS description,
                       b.currency     AS currency,
                       CAST(0 AS NUMERIC) AS debit,
                       b.gross_amount AS credit
                FROM supplier_bills b
                WHERE b.company_id = :companyId AND b.supplier_id = :supplierId
                  AND b.status IN ('MATCHED', 'APPROVED', 'PARTIALLY_PAID', 'PAID')
                UNION ALL
                SELECT p.payment_date, 2, p.id, 'PAYMENT', p.payment_number,
                       'Payment (' || replace(p.tender_type, '_', ' ') || ')'
                           || COALESCE(', ref ' || NULLIF(p.bank_reference, ''), '')
                           || COALESCE(', of which WHT withheld '
                                  || to_char(p.wht_amount, 'FM999,999,999,999,990.00'), ''),
                       p.currency, p.amount, CAST(0 AS NUMERIC)
                FROM ap_payments p
                WHERE p.company_id = :companyId AND p.supplier_id = :supplierId
                UNION ALL
                SELECT s.payment_date, 2, s.id, 'PAYMENT', s.payment_number,
                       'Payment run (' || replace(s.tender_type, '_', ' ') || ')'
                           || COALESCE(', ref ' || NULLIF(s.bank_reference, ''), ''),
                       s.currency, s.share, CAST(0 AS NUMERIC)
                FROM run_shares s
                UNION ALL
                SELECT CAST((p.reversed_at AT TIME ZONE :tz) AS DATE), 3, p.id, 'PAYMENT_REVERSAL',
                       p.payment_number, 'Payment reversed (cheque returned unpaid)',
                       p.currency, CAST(0 AS NUMERIC), p.amount
                FROM ap_payments p
                WHERE p.company_id = :companyId AND p.supplier_id = :supplierId
                  AND p.reversed_at IS NOT NULL
                UNION ALL
                SELECT CAST((s.reversed_at AT TIME ZONE :tz) AS DATE), 3, s.id, 'PAYMENT_REVERSAL',
                       s.payment_number, 'Payment reversed (cheque returned unpaid)',
                       s.currency, CAST(0 AS NUMERIC), s.share
                FROM run_shares s
                WHERE s.reversed_at IS NOT NULL
                UNION ALL
                SELECT d.note_date, 4, d.id, 'DEBIT_NOTE', d.debit_note_number,
                       'Debit note: ' || d.reason,
                       d.currency, d.amount, CAST(0 AS NUMERIC)
                FROM ap_debit_notes d
                WHERE d.company_id = :companyId AND d.supplier_id = :supplierId
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopeGuard                 scopeGuard;

    public ApSupplierLedgerQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = new NamedParameterJdbcTemplate(jdbc);
        this.scopeGuard = scopeGuard;
    }

    /**
     * Resolves the supplier an AP read names — by uid (what the screen sends) or by id (what the
     * original endpoints took) — INSIDE {@code companyId}. A uid or id from another company is "not
     * found", never a silent widen.
     */
    public ApSupplierRefDto resolveSupplier(Long companyId, Long supplierId, String supplierUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        boolean byUid = supplierUid != null && !supplierUid.isBlank();
        if (!byUid && supplierId == null) {
            throw new IllegalArgumentException("Choose a supplier.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("companyId", companyId);
        String where;
        if (byUid) {
            where = "uid = :uid";
            p.addValue("uid", supplierUid.trim());
        } else {
            where = "id = :id";
            p.addValue("id", supplierId);
        }
        List<ApSupplierRefDto> found = jdbc.query(
                "SELECT id, uid, code, display_name, tin, vrn FROM suppliers"
                        + " WHERE company_id = :companyId AND " + where,
                p,
                (rs, n) -> new ApSupplierRefDto(rs.getLong("id"), rs.getString("uid"),
                        rs.getString("code"), rs.getString("display_name"),
                        rs.getString("tin"), rs.getString("vrn")));
        if (found.isEmpty()) {
            throw new NotFoundException("Supplier not found.");
        }
        return found.get(0);
    }

    /**
     * The statement for one supplier.
     *
     * @param fromDate first day shown; null = from the supplier's first movement (no b/f balance)
     * @param toDate   last day shown (inclusive); null = today
     * @param currency the statement currency; null/blank = the company's base currency
     */
    public ApSupplierLedgerDto ledger(Long companyId, Long supplierId, String supplierUid,
                                      LocalDate fromDate, LocalDate toDate, String currency) {
        ApSupplierRefDto supplier = resolveSupplier(companyId, supplierId, supplierUid);
        CompanyRow co = loadCompany(companyId);

        LocalDate to = toDate != null ? toDate : LocalDate.now();
        if (fromDate != null && fromDate.isAfter(to)) {
            throw new IllegalArgumentException("The start date must be on or before the end date.");
        }
        String ccy = currency != null && !currency.isBlank()
                ? currency.trim().toUpperCase() : co.baseCurrency();

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("supplierId", supplier.id())
                .addValue("tz", co.timeZone())
                .addValue("currency", ccy)
                .addValue("toDate", to)
                .addValue("fromDate", fromDate);

        // Balance = what we owe = credits − debits.
        BigDecimal opening = BigDecimal.ZERO;
        if (fromDate != null) {
            opening = jdbc.queryForObject(ENTRIES_CTE + """
                    SELECT COALESCE(SUM(credit - debit), 0)
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
        List<ApSupplierLedgerRowDto> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            BigDecimal debit  = (BigDecimal) r[4];
            BigDecimal credit = (BigDecimal) r[5];
            running     = running.add(credit).subtract(debit);
            totalDebit  = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
            rows.add(new ApSupplierLedgerRowDto((LocalDate) r[0],
                    ApLedgerEntryType.valueOf((String) r[1]),
                    (String) r[2], (String) r[3], debit, credit, running));
        }

        return new ApSupplierLedgerDto(co.header(), supplier.uid(), supplier.code(), supplier.name(),
                supplier.tin(), supplier.vrn(), fromDate, to, ccy, opening, rows,
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
