package com.erp.modules.gl.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sales-vs-GL tie-out for a date range (ACC-02), in base currency. The sales side is the finalised
 * invoices of the range (the same figures the VAT return reads); the GL side is what the sales
 * postings (SALES and SALES_REVERSAL journals) credited to the mapped revenue and VAT accounts.
 * A non-zero difference means sales are missing from the ledger — typically the open posting
 * exceptions — or a void reversed in a different range.
 *
 * @param salesNet          taxable sales (net of VAT) on finalised invoices
 * @param salesVat          output VAT on finalised invoices
 * @param glRevenue         net credit to the sales revenue account from sales postings
 * @param glVat             net credit to the VAT payable account from sales postings
 * @param revenueDifference salesNet − glRevenue
 * @param vatDifference     salesVat − glVat
 */
public record GlSalesTieOutDto(
        LocalDate from,
        LocalDate to,
        BigDecimal salesNet,
        BigDecimal salesVat,
        BigDecimal glRevenue,
        BigDecimal glVat,
        BigDecimal revenueDifference,
        BigDecimal vatDifference
) {}
