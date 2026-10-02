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
     * Output: FINALISED invoices whose finalised_at falls in [start, end].
     * Input:  matched/approved supplier bills (status not DRAFT or HELD) with bill_date in [start, end].
     */
    public VatReturnComputationDto compute(Long companyId, LocalDate start, LocalDate end) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // --- Output VAT (via Sales DTO method, tax → sales, acyclic per D-10) ---
        VatOutputSummaryDto outputSummary = salesService.findVatSummaryForPeriod(companyId, start, end);

        Map<String, BandTotalsDto> byBand = new LinkedHashMap<>();
        outputSummary.byBand().forEach((band, b) ->
                byBand.put(band, new BandTotalsDto(b.taxableBase(), b.outputVat())));

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

        return new VatReturnComputationDto(byBand, outputSummary.totalOutputVat(),
                inputVat != null ? inputVat : BigDecimal.ZERO);
    }

    /** Minor units of the company's base currency (0 for TZS), from the currencies master. */
    private int baseScale(Long companyId) {
        String base = jdbc.query("SELECT base_currency FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, companyId);
        return minorUnits.of(base);
    }
}
