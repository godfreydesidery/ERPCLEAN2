package com.erp.modules.tax.service;

import com.erp.modules.sales.domain.dto.VatOutputSummaryDto;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto.BandTotalsDto;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads output-by-band (via Sales DTO method) and input (direct supplier_bills scalar
 * projection) for a VAT return period (ADR-0017 D-6 / D-10).
 *
 * <p>Input VAT is read via a direct scalar JDBC query on supplier_bills — deliberately NOT
 * via an AP service method — to avoid the ap↔tax module cycle (ADR-0017 D-10).
 * Output VAT is read via SalesInvoiceService.findVatSummaryForPeriod (tax→sales, acyclic).
 */
@Service
@Transactional(readOnly = true)
public class VatReturnComputationReader {

    private final SalesInvoiceService salesService;
    private final JdbcTemplate        jdbc;
    private final ScopeGuard          scopeGuard;
    private final CurrencyMinorUnits  minorUnits;

    public VatReturnComputationReader(SalesInvoiceService salesService,
                                      JdbcTemplate jdbc,
                                      ScopeGuard scopeGuard,
                                      CurrencyMinorUnits minorUnits) {
        this.salesService = salesService;
        this.jdbc         = jdbc;
        this.scopeGuard   = scopeGuard;
        this.minorUnits   = minorUnits;
    }

    /**
     * Compute output-by-band and input for the given company + period (accrual basis).
     * <ul>
     *   <li>Output = invoices finalised in [start, end] (including ones voided later — ACC-24)
     *       − invoices VOIDED in [start, end] (the void month carries the negative)
     *       − AR credit-note VAT (incl. sales returns) with note_date in [start, end] (ACC-06).</li>
     *   <li>Input = matched/approved supplier bills (not DRAFT or HELD) with bill_date in
     *       [start, end] − AP debit-note VAT (incl. purchase returns) with note_date in
     *       [start, end] (ACC-06).</li>
     * </ul>
     * Each component is the same base-currency amount its own GL journal moved on VAT Payable
     * (2200) or VAT Input (1400), so the filing settlement that clears these figures leaves both
     * control accounts at zero. Either total may be negative (a month of more credits than sales).
     */
    public VatReturnComputationDto compute(Long companyId, LocalDate start, LocalDate end) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // --- Output VAT (via Sales DTO method, tax → sales, acyclic per D-10) ---
        VatOutputSummaryDto outputSummary = salesService.findVatSummaryForPeriod(companyId, start, end);

        Map<String, BandTotalsDto> byBand = new LinkedHashMap<>();
        outputSummary.byBand().forEach((band, b) ->
                byBand.put(band, new BandTotalsDto(b.taxableBase(), b.outputVat())));

        // ACC-24: invoices voided in this period — a negative in the void month, by band (the GL
        // void reversal debits 2200 on the day of the void). The month the invoice was finalised
        // keeps it, so a FILED earlier return is never disturbed.
        VatOutputSummaryDto voided = salesService.findVatVoidSummaryForPeriod(companyId, start, end);
        voided.byBand().forEach((band, b) -> byBand.merge(band,
                new BandTotalsDto(b.taxableBase().negate(), b.outputVat().negate()),
                VatReturnComputationReader::addBand));
        BigDecimal totalOutput = outputSummary.totalOutputVat().subtract(voided.totalOutputVat());

        // --- Input VAT (direct scalar projection on supplier_bills — avoids ap↔tax cycle D-10) ---
        // Statuses that mean "posted payable": MATCHED, APPROVED, PARTIALLY_PAID, PAID.
        // DRAFT and HELD are excluded (BR-VAT-04).
        //
        // BASE CURRENCY: vat_amount is in the BILL's currency. Each bill is converted at the rate
        // stamped on it at match (supplier_bills.fx_rate, ADR-0036 D-4 — the rate BillMatchServiceImpl
        // used for its DR VAT_INPUT leg on the same bill date) and rounded HALF_UP to the base
        // currency's minor units PER BILL, as that leg was. fx_rate = 1 (a base-currency bill) is the
        // identity, so a single-currency company's input VAT is unchanged. baseScale is an int read
        // from the currencies master, never caller text.
        int baseScale = baseScale(companyId);
        BigDecimal inputVat = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(CASE WHEN fx_rate = 1 THEN vat_amount
                                         ELSE ROUND(vat_amount * fx_rate, %d) END), 0)
                FROM   supplier_bills
                WHERE  company_id = ?
                  AND  status IN ('MATCHED','APPROVED','PARTIALLY_PAID','PAID')
                  AND  bill_date BETWEEN ? AND ?
                """.formatted(baseScale),
                BigDecimal.class,
                companyId, start, end);

        // ACC-06: AR credit notes (standalone and sales returns) dated in the period. Each one
        // debited VAT Payable at raise by round(vat_amount × fx_rate) to base minor units (even at
        // rate 1, as ArCreditNoteServiceImpl does) — so output VAT falls by exactly what the ledger already relieved. A credit
        // note carries no band split; VAT-bearing ones reduce the STANDARD band (the only band with
        // VAT), zero-VAT ones change no band.
        Map<String, Object> cn = jdbc.queryForMap(
                """
                SELECT COALESCE(SUM(ROUND(vat_amount * fx_rate, %1$d)), 0) AS vat,
                       COALESCE(SUM(CASE WHEN vat_amount = 0 THEN 0
                                         ELSE ROUND(net_amount * fx_rate, %1$d) END), 0) AS std_net
                FROM   ar_credit_notes
                WHERE  company_id = ?
                  AND  note_date BETWEEN ? AND ?
                """.formatted(baseScale),
                companyId, start, end);
        BigDecimal cnVat    = (BigDecimal) cn.get("vat");
        BigDecimal cnStdNet = (BigDecimal) cn.get("std_net");
        if (cnVat.signum() != 0 || cnStdNet.signum() != 0) {
            byBand.merge("STANDARD", new BandTotalsDto(cnStdNet.negate(), cnVat.negate()),
                    VatReturnComputationReader::addBand);
        }
        totalOutput = totalOutput.subtract(cnVat);

        // ACC-06: AP debit notes (standalone and purchase returns) dated in the period. Each one
        // credited VAT Input at raise by round(vat_amount × fx_rate) (ApDebitNoteServiceImpl), so
        // input VAT falls by the same figure.
        BigDecimal dnVat = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(ROUND(vat_amount * fx_rate, %d)), 0)
                FROM   ap_debit_notes
                WHERE  company_id = ?
                  AND  note_date BETWEEN ? AND ?
                """.formatted(baseScale),
                BigDecimal.class,
                companyId, start, end);

        // ACC-13 / PAR-08: input VAT claimed on cash/bank expense entries — the DR VAT Input legs of
        // CASH_DIRECT journals dated in the period (only the direct-entry VAT option posts there).
        BigDecimal cashVat = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0)
                FROM   journal_lines jl
                JOIN   journal_entries je ON je.id = jl.entry_id
                JOIN   gl_configs g ON g.company_id = je.company_id
                                   AND g.config_key = 'VAT_INPUT' AND g.account_id = jl.account_id
                WHERE  je.company_id = ?
                  AND  je.source_type = 'CASH_DIRECT'
                  AND  je.posting_date BETWEEN ? AND ?
                """,
                BigDecimal.class,
                companyId, start, end);

        BigDecimal totalInput = (inputVat != null ? inputVat : BigDecimal.ZERO)
                .subtract(dnVat != null ? dnVat : BigDecimal.ZERO)
                .add(cashVat != null ? cashVat : BigDecimal.ZERO);
        return new VatReturnComputationDto(byBand, totalOutput, totalInput);
    }

    private static BandTotalsDto addBand(BandTotalsDto a, BandTotalsDto b) {
        return new BandTotalsDto(a.taxableBase().add(b.taxableBase()),
                a.outputVat().add(b.outputVat()));
    }

    /** Minor units of the company's base currency (0 for TZS), from the currencies master. */
    private int baseScale(Long companyId) {
        String base = jdbc.query("SELECT base_currency FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, companyId);
        return minorUnits.of(base);
    }
}
