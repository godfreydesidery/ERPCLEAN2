package com.erp.modules.tax.service;

import com.erp.modules.sales.domain.dto.VatOutputSummaryDto;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto.BandTotalsDto;
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

    public VatReturnComputationReader(SalesInvoiceService salesService,
                                      JdbcTemplate jdbc,
                                      ScopeGuard scopeGuard) {
        this.salesService = salesService;
        this.jdbc         = jdbc;
        this.scopeGuard   = scopeGuard;
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
        BigDecimal inputVat = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(vat_amount), 0)
                FROM   supplier_bills
                WHERE  company_id = ?
                  AND  status IN ('MATCHED','APPROVED','PARTIALLY_PAID','PAID')
                  AND  bill_date BETWEEN ? AND ?
                """,
                BigDecimal.class,
                companyId, start, end);

        return new VatReturnComputationDto(byBand, outputSummary.totalOutputVat(),
                inputVat != null ? inputVat : BigDecimal.ZERO);
    }
}
