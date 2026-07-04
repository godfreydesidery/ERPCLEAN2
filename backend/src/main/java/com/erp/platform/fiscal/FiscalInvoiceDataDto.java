package com.erp.platform.fiscal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Provider-agnostic snapshot of a FINALISED sales invoice handed to a {@link FiscalisationProvider}
 * (ADR-0049 §3). Plain strings/BigDecimals only — deliberately carries no {@code sales} module
 * entity, so the real TRA adapter (a future {@code com.erp.platform.fiscal.tra} component) depends
 * only on this seam, never on the sales module.
 *
 * @param invoiceUid     the finalised invoice's uid
 * @param invoiceNumber  the assigned invoice number (e.g. {@code INV-0001})
 * @param companyId      the selling company's internal id (diagnostics only — never sent to a device)
 * @param branchId       the selling branch's internal id (diagnostics only — never sent to a device)
 * @param companyTin     the seller's TIN ({@code companies.tax_id})
 * @param companyVrn     the seller's VAT registration number ({@code companies.vrn})
 * @param customerName   the buyer's display name
 * @param customerTin    the buyer's TIN — deferred (ADR-0049 §8.9); always {@code null} today
 * @param currency       ISO 4217 document currency
 * @param netTotal       invoice net total
 * @param vatTotal       invoice VAT total
 * @param grossTotal     invoice gross total
 * @param finalisedAt    when the invoice was finalised
 * @param lines          one entry per invoice line
 */
public record FiscalInvoiceDataDto(
        String invoiceUid,
        String invoiceNumber,
        Long companyId,
        Long branchId,
        String companyTin,
        String companyVrn,
        String customerName,
        String customerTin,
        String currency,
        BigDecimal netTotal,
        BigDecimal vatTotal,
        BigDecimal grossTotal,
        Instant finalisedAt,
        List<Line> lines) {

    /**
     * One invoice line, snapshotted at issue time.
     *
     * @param description     product/line description (the snapshotted product name)
     * @param quantity        line quantity, in {@code unitCode} units
     * @param unitCode        the unit name/code the line was sold in
     * @param unitPrice       applied unit price (net)
     * @param lineNet         line net amount
     * @param vatRatePercent  VAT rate expressed as a percentage (e.g. {@code 18.00} for 18%)
     * @param lineVat         line VAT amount
     * @param taxCode         the VAT classification (e.g. {@code STANDARD}, {@code ZERO_RATED}, {@code EXEMPT})
     */
    public record Line(
            String description,
            BigDecimal quantity,
            String unitCode,
            BigDecimal unitPrice,
            BigDecimal lineNet,
            BigDecimal vatRatePercent,
            BigDecimal lineVat,
            String taxCode) {
    }
}
