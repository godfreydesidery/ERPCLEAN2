package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.VatScheduleDto;
import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.repository.VatReturnRepository;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPT-14 / PAR-06: the per-document VAT schedules behind a return — what TRA's sales and purchases
 * listings ask for (date, number, party, TIN, VRN, net, VAT, EFD/fiscal number).
 *
 * <p>Each schedule reads the SAME documents, windows and base-currency arithmetic as
 * {@link VatReturnComputationReader}, so its VAT column sums to the return's output / input VAT:
 * <ul>
 *   <li>Sales: invoices finalised in the period (including ones voided later), invoices voided in the
 *       period (negative), AR credit notes dated in the period (negative).</li>
 *   <li>Purchases: matched/approved bills dated in the period, AP debit notes dated in the period
 *       (negative).</li>
 * </ul>
 * Scalar SQL across the sales / AR / AP / parties tables: the tax module imports none of their
 * entities (ADR-0017 D-10, the supplier_bills precedent). Every join is company-scoped.
 */
@Component
@Transactional(readOnly = true)
public class VatReturnScheduleQuery {

    private final VatReturnRepository returns;
    private final JdbcTemplate        jdbc;
    private final ScopeGuard          scopeGuard;
    private final CurrencyMinorUnits  minorUnits;

    public VatReturnScheduleQuery(VatReturnRepository returns, JdbcTemplate jdbc,
                                  ScopeGuard scopeGuard, CurrencyMinorUnits minorUnits) {
        this.returns    = returns;
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
        this.minorUnits = minorUnits;
    }

    /** The sales (output VAT) schedule of the return. */
    public VatScheduleDto sales(String vatReturnUid) {
        VatReturn r = load(vatReturnUid);
        Long companyId = r.getCompanyId();
        int scale = baseScale(companyId);
        // Window for the timestamp columns: the SAME derivation as
        // SalesInvoiceServiceImpl.findVatSummaryForPeriod (keep the three in step).
        Timestamp from = Timestamp.from(r.getPeriodStart().atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to   = Timestamp.from(r.getPeriodEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        List<VatScheduleDto.Row> rows = jdbc.query("""
                SELECT * FROM (
                    SELECT (si.finalised_at AT TIME ZONE 'UTC')::date AS doc_date, 'INVOICE' AS doc_type,
                           si.invoice_number AS doc_number, NULL AS our_ref,
                           c.display_name AS party, c.tin, c.vrn,
                           CASE WHEN si.fx_rate = 1 THEN si.net_total_amount
                                ELSE ROUND(si.net_total_amount * si.fx_rate, %1$d) END AS net,
                           CASE WHEN si.fx_rate = 1 THEN si.vat_total_amount
                                ELSE ROUND(si.vat_total_amount * si.fx_rate, %1$d) END AS vat,
                           fr.fiscal_number
                    FROM sales_invoices si
                    LEFT JOIN customers c ON c.id = si.customer_id AND c.company_id = si.company_id
                    LEFT JOIN fiscal_receipts fr ON fr.sales_invoice_id = si.id
                         AND fr.company_id = si.company_id AND fr.status = 'ISSUED'
                    WHERE si.company_id = ? AND si.status IN ('FINALISED', 'VOID')
                      AND si.finalised_at >= ? AND si.finalised_at < ?
                    UNION ALL
                    SELECT (si.voided_at AT TIME ZONE 'UTC')::date, 'VOID',
                           si.invoice_number, NULL,
                           c.display_name, c.tin, c.vrn,
                           -(CASE WHEN si.fx_rate = 1 THEN si.net_total_amount
                                  ELSE ROUND(si.net_total_amount * si.fx_rate, %1$d) END),
                           -(CASE WHEN si.fx_rate = 1 THEN si.vat_total_amount
                                  ELSE ROUND(si.vat_total_amount * si.fx_rate, %1$d) END),
                           fr.fiscal_number
                    FROM sales_invoices si
                    LEFT JOIN customers c ON c.id = si.customer_id AND c.company_id = si.company_id
                    LEFT JOIN fiscal_receipts fr ON fr.sales_invoice_id = si.id
                         AND fr.company_id = si.company_id
                    WHERE si.company_id = ? AND si.status = 'VOID' AND si.finalised_at IS NOT NULL
                      AND si.voided_at >= ? AND si.voided_at < ?
                    UNION ALL
                    SELECT cn.note_date, 'CREDIT_NOTE', cn.credit_note_number, NULL,
                           c.display_name, c.tin, c.vrn,
                           -ROUND(cn.net_amount * cn.fx_rate, %1$d),
                           -ROUND(cn.vat_amount * cn.fx_rate, %1$d),
                           NULL
                    FROM ar_credit_notes cn
                    LEFT JOIN customers c ON c.id = cn.customer_id AND c.company_id = cn.company_id
                    WHERE cn.company_id = ? AND cn.note_date BETWEEN ? AND ?
                      AND cn.created_at <= ?
                ) s
                ORDER BY doc_date, doc_number
                """.formatted(scale),
                VatReturnScheduleQuery::row,
                companyId, from, to,
                companyId, from, to,
                companyId, Date.valueOf(r.getPeriodStart()), Date.valueOf(r.getPeriodEnd()),
                cutoff(r));
        return schedule(r, rows);
    }

    /** The purchases (input VAT) schedule of the return. */
    public VatScheduleDto purchases(String vatReturnUid) {
        VatReturn r = load(vatReturnUid);
        Long companyId = r.getCompanyId();
        int scale = baseScale(companyId);
        Date from = Date.valueOf(r.getPeriodStart());
        Date to   = Date.valueOf(r.getPeriodEnd());

        List<VatScheduleDto.Row> rows = jdbc.query("""
                SELECT * FROM (
                    SELECT b.bill_date AS doc_date, 'BILL' AS doc_type,
                           b.supplier_invoice_no AS doc_number, b.bill_number AS our_ref,
                           s.display_name AS party, s.tin, s.vrn,
                           CASE WHEN b.fx_rate = 1 THEN b.net_amount
                                ELSE ROUND(b.net_amount * b.fx_rate, %1$d) END AS net,
                           CASE WHEN b.fx_rate = 1 THEN b.vat_amount
                                ELSE ROUND(b.vat_amount * b.fx_rate, %1$d) END AS vat,
                           NULL AS fiscal_number
                    FROM supplier_bills b
                    LEFT JOIN suppliers s ON s.id = b.supplier_id AND s.company_id = b.company_id
                    WHERE b.company_id = ?
                      AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID','PAID')
                      AND b.bill_date BETWEEN ? AND ?
                      AND b.created_at <= ?
                    UNION ALL
                    SELECT dn.note_date, 'DEBIT_NOTE', dn.debit_note_number, NULL,
                           s.display_name, s.tin, s.vrn,
                           -ROUND(dn.net_amount * dn.fx_rate, %1$d),
                           -ROUND(dn.vat_amount * dn.fx_rate, %1$d),
                           NULL
                    FROM ap_debit_notes dn
                    LEFT JOIN suppliers s ON s.id = dn.supplier_id AND s.company_id = dn.company_id
                    WHERE dn.company_id = ? AND dn.note_date BETWEEN ? AND ?
                      AND dn.created_at <= ?
                ) p
                ORDER BY doc_date, doc_number
                """.formatted(scale),
                VatReturnScheduleQuery::row,
                companyId, from, to, cutoff(r),
                companyId, from, to, cutoff(r));
        return schedule(r, rows);
    }

    // -------------------------------------------------------------------------

    private VatReturn load(String uid) {
        VatReturn r = Lookups.orNotFound(returns.findByUid(uid), "VatReturn", uid);
        // Scope from the LOADED return, never a caller parameter.
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        return r;
    }

    /**
     * A FILED return's schedule lists only notes and bills that existed when it was filed: one
     * back-dated into the period afterwards was never on the return (a later return carries it as an
     * adjustment), so the schedule keeps reproducing the filed figures. A DRAFT takes them all.
     */
    private static Timestamp cutoff(VatReturn r) {
        return r.getStatus() == VatReturnStatus.FILED && r.getFiledAt() != null
                ? Timestamp.from(r.getFiledAt())
                : Timestamp.valueOf("9999-12-31 00:00:00");
    }

    private static VatScheduleDto schedule(VatReturn r, List<VatScheduleDto.Row> rows) {
        BigDecimal net = rows.stream().map(VatScheduleDto.Row::net)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vat = rows.stream().map(VatScheduleDto.Row::vat)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new VatScheduleDto(r.getReturnNumber(), r.getCompanyId(), r.getPeriodStart(),
                r.getPeriodEnd(), r.getStatus(), rows, net, vat);
    }

    private static VatScheduleDto.Row row(ResultSet rs, int n) throws SQLException {
        Date d = rs.getDate("doc_date");
        return new VatScheduleDto.Row(
                d != null ? d.toLocalDate() : null,
                rs.getString("doc_type"),
                rs.getString("doc_number"),
                rs.getString("our_ref"),
                rs.getString("party"),
                rs.getString("tin"),
                rs.getString("vrn"),
                orZero(rs.getBigDecimal("net")),
                orZero(rs.getBigDecimal("vat")),
                rs.getString("fiscal_number"));
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Minor units of the company's base currency (0 for TZS), from the currencies master. */
    private int baseScale(Long companyId) {
        String base = jdbc.query("SELECT base_currency FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, companyId);
        return minorUnits.of(base);
    }
}
